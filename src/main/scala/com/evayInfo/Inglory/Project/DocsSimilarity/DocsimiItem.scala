package com.evayInfo.Inglory.Project.DocsSimilarity

import java.text.SimpleDateFormat
import java.util.{Calendar, Date}

import com.evayInfo.Inglory.Project.Recommend.UtilTool
import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.client.Scan
import org.apache.hadoop.hbase.mapreduce.TableInputFormat
import org.apache.hadoop.hbase.protobuf.ProtobufUtil
import org.apache.hadoop.hbase.util.{Base64, Bytes}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.ml.feature.StringIndexer
import org.apache.spark.mllib.linalg.distributed.{CoordinateMatrix, MatrixEntry}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.{SparkConf, SparkContext}

/**
 * Created by sunlu on 17/8/22.
 */
object DocsimiItem {

  def convertScanToString(scan: Scan) = {
    val proto = ProtobufUtil.toScan(scan)
    Base64.encodeBytes(proto.toByteArray)
  }

  def SetLogger = {
    Logger.getLogger("org").setLevel(Level.OFF)
    Logger.getLogger("com").setLevel(Level.OFF)
    System.setProperty("spark.ui.showConsoleProgress", "false")
    Logger.getRootLogger().setLevel(Level.OFF)
  }

  case class YlzxSchema(itemString: String, title: String, manuallabel: String, time: String, websitename: String, content: String)

  case class LogView(CREATE_BY_ID: String, CREATE_TIME: Long, REQUEST_URI: String, PARAMS: String)

  case class LogView2(userString: String, itemString: String, CREATE_TIME: Long, value: Double)
  case class ItemSimi( itemid1: Long, itemid2: Long, similar: Double)


  def getYlzxRDD(ylzxTable: String, sc: SparkContext): RDD[YlzxSchema] = {
    //定义时间格式
    // val dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy hh:mm:ss z", Locale.ENGLISH)
    val dateFormat = new SimpleDateFormat("yyyy-MM-dd") // yyyy-MM-dd HH:mm:ss或者 yyyy-MM-dd

    //获取当前时间
    val now: Date = new Date()
    //对时间格式尽心格式化
    val today = dateFormat.format(now)
    //把时间转换成long类型
    val todayL = dateFormat.parse(today).getTime
    //获取N天的时间，并把时间转换成long类型
    val cal: Calendar = Calendar.getInstance()
    val N = 20
    //  cal.add(Calendar.DATE, -N)//获取N天前或N天后的时间，-2为2天前
    cal.add(Calendar.YEAR, -N) //获取N年或N年后的时间，-2为2年前
    //    cal.add(Calendar.MONTH, -N) //获取N月或N月后的时间，-2为2月前

    val nDaysAgo = dateFormat.format(cal.getTime())
    val nDaysAgoL = dateFormat.parse(nDaysAgo).getTime

    val conf = HBaseConfiguration.create() //在HBaseConfiguration设置可以将扫描限制到部分列，以及限制扫描的时间范围
    //设置查询的表名
    conf.set(TableInputFormat.INPUT_TABLE, ylzxTable) //设置输入表名 第一个参数yeeso-test-ywk_webpage

    //扫描整个表中指定的列和列簇
    val scan = new Scan()
    scan.addColumn(Bytes.toBytes("p"), Bytes.toBytes("t")) //title
    scan.addColumn(Bytes.toBytes("p"), Bytes.toBytes("manuallabel")) //label
    scan.addColumn(Bytes.toBytes("f"), Bytes.toBytes("mod")) //time
    scan.addColumn(Bytes.toBytes("p"), Bytes.toBytes("websitename")) //websitename
    scan.addColumn(Bytes.toBytes("p"), Bytes.toBytes("c")) //content
    conf.set(TableInputFormat.SCAN, convertScanToString(scan))

    val hBaseRDD = sc.newAPIHadoopRDD(conf, classOf[TableInputFormat],
      classOf[org.apache.hadoop.hbase.io.ImmutableBytesWritable],
      classOf[org.apache.hadoop.hbase.client.Result])
    //提取hbase数据，并对数据进行过滤
    val hbaseRDD = hBaseRDD.map { case (k, v) => {
      val urlID = k.get()
      val title = v.getValue(Bytes.toBytes("p"), Bytes.toBytes("t")) //标题列
      val manuallabel = v.getValue(Bytes.toBytes("p"), Bytes.toBytes("manuallabel")) //标签列
      val time = v.getValue(Bytes.toBytes("f"), Bytes.toBytes("mod")) //时间列
      val webName = v.getValue(Bytes.toBytes("p"), Bytes.toBytes("websitename")) //websitename列
      val content = v.getValue(Bytes.toBytes("p"), Bytes.toBytes("c")) //content列
      (urlID, title, manuallabel, time, webName, content)
    }
    }.filter(x => null != x._2 & null != x._3 & null != x._4 & null != x._5 & null != x._6).
      map { x => {
        val urlID_1 = Bytes.toString(x._1)
        val title_1 = if (null != x._2) Bytes.toString(x._2) else ""
        val manuallabel_1 = if (null != x._3) Bytes.toString(x._3) else ""
        //时间格式转化
        val time = Bytes.toLong(x._4)

        val websitename_1 = if (null != x._5) Bytes.toString(x._5) else ""
        val content_1 = Bytes.toString(x._6)
        (urlID_1, title_1, manuallabel_1, time, websitename_1, content_1)
      }
      }.filter(x => {
      x._2.length >= 2
    }).filter(x => x._4 <= todayL & x._4 >= nDaysAgoL).map(x => {
      val date: Date = new Date(x._4)
      val time = dateFormat.format(date)
      val content = x._6.replace("&nbsp;", "").replaceAll("\\uFFFD", "").replaceAll("([\\ud800-\\udbff\\udc00-\\udfff])", "")
      YlzxSchema(x._1, x._2, x._3, time, x._5, content)
    })

    hbaseRDD

  }

  def getLogsRDD(logsTable: String, sc: SparkContext): RDD[LogView2] = {

    val conf = HBaseConfiguration.create() //在HBaseConfiguration设置可以将扫描限制到部分列，以及限制扫描的时间范围
    //设置查询的表名
    conf.set(TableInputFormat.INPUT_TABLE, logsTable) //设置输入表名 第一个参数yeeso-test-ywk_webpage

    //扫描整个表中指定的列和列簇
    val scan = new Scan()
    scan.addColumn(Bytes.toBytes("info"), Bytes.toBytes("cREATE_BY_ID")) //cREATE_BY_ID
    scan.addColumn(Bytes.toBytes("info"), Bytes.toBytes("cREATE_TIME")) //cREATE_TIME
    scan.addColumn(Bytes.toBytes("info"), Bytes.toBytes("rEQUEST_URI")) //rEQUEST_URI
    scan.addColumn(Bytes.toBytes("info"), Bytes.toBytes("pARAMS")) //pARAMS
    conf.set(TableInputFormat.SCAN, convertScanToString(scan))

    val hBaseRDD = sc.newAPIHadoopRDD(conf, classOf[TableInputFormat],
      classOf[org.apache.hadoop.hbase.io.ImmutableBytesWritable],
      classOf[org.apache.hadoop.hbase.client.Result])
    //提取hbase数据，并对数据进行过滤
    val hbaseRDD = hBaseRDD.map { case (k, v) => {
      val rowkey = k.get()
      val userID = v.getValue(Bytes.toBytes("info"), Bytes.toBytes("cREATE_BY_ID")) //cREATE_BY_ID
      val creatTime = v.getValue(Bytes.toBytes("info"), Bytes.toBytes("cREATE_TIME")) //cREATE_TIME
      val requestURL = v.getValue(Bytes.toBytes("info"), Bytes.toBytes("rEQUEST_URI")) //rEQUEST_URI
      val parmas = v.getValue(Bytes.toBytes("info"), Bytes.toBytes("pARAMS")) //pARAMS
      (userID, creatTime, requestURL, parmas)
    }
    }.filter(x => null != x._1 & null != x._2 & null != x._3 & null != x._4).
      map { x => {
        val userID = Bytes.toString(x._1)
        val creatTime = Bytes.toString(x._2)
        //定义时间格式
        val dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss") // yyyy-MM-dd HH:mm:ss或者 yyyy-MM-dd
        val dateFormat2 = new SimpleDateFormat("yyyy-MM-dd") // yyyy-MM-dd HH:mm:ss或者 yyyy-MM-dd
        val creatTimeD = dateFormat.parse(creatTime)
        val creatTimeS = dateFormat.format(creatTimeD)
        val creatTimeL = dateFormat2.parse(creatTimeS).getTime

        val requestURL = Bytes.toString(x._3)
        val parmas = Bytes.toString(x._4)
        LogView(userID, creatTimeL, requestURL, parmas)
      }
      }.filter(x => x.REQUEST_URI.contains("getContentById.do") || x.REQUEST_URI.contains("like/add.do") ||
      x.REQUEST_URI.contains("favorite/add.do") || x.REQUEST_URI.contains("favorite/delete.do") ||
      x.REQUEST_URI.contains("addFavorite.do") || x.REQUEST_URI.contains("delFavorite.do")
    ).
      filter(_.PARAMS.toString.length >= 10).
      map(x => {
        val userID = x.CREATE_BY_ID.toString
        //        val reg2 = """id=(\w+\.){2}\w+.*,""".r
        val reg2 =
          """id=\S*,|id=\S*}""".r
        val urlString = reg2.findFirstIn(x.PARAMS.toString).toString.replace("Some(id=", "").replace(",)", "").replace("})", "")
        val time = x.CREATE_TIME
        val value = 1.0
        val rating = x.REQUEST_URI match {
          case r if (r.contains("getContentById.do")) => 0.2 * value
          case r if (r.contains("like/add.do")) => 0.3 * value
          case r if (r.contains("favorite/add.do")) => 0.5 * value
          case r if (r.contains("addFavorite.do")) => 0.5 * value //0.5
          case r if (r.contains("favorite/delete.do")) => -0.5 * value
          case r if (r.contains("delFavorite.do")) => -0.5 * value //-0.5
          case _ => 0.0 * value
        }

        LogView2(userID, urlString, time, rating)
      }).filter(_.itemString.length >= 5).filter(_.userString.length >= 5).
      map(x => {
        val userString = x.userString
        val itemString = x.itemString
        val time = x.CREATE_TIME
        val value = x.value

        val rating = time match {
          case x if (x >= UtilTool.get3Dasys()) => 0.9 * value
          case x if (x >= UtilTool.get7Dasys() && x < UtilTool.get3Dasys()) => 0.8 * value
          case x if (x >= UtilTool.getHalfMonth() && x < UtilTool.get7Dasys()) => 0.7 * value
          case x if (x >= UtilTool.getOneMonth() && x < UtilTool.getHalfMonth()) => 0.6 * value
          case x if (x >= UtilTool.getSixMonth() && x < UtilTool.getOneMonth()) => 0.5 * value
          case x if (x >= UtilTool.getOneYear() && x < UtilTool.getSixMonth()) => 0.4 * value
          case x if (x < UtilTool.getOneYear()) => 0.3 * value
          case _ => 0.0
        }

        //val rating = rValue(time, value)
        LogView2(userString, itemString, time, rating)
      })

    hbaseRDD
  }


  def main(args: Array[String]) {
    SetLogger
    val sparkConf = new SparkConf().setAppName(s"DocsimiItem") //.setMaster("local[*]").set("spark.executor.memory", "2g")
    val spark = SparkSession.builder().config(sparkConf).getOrCreate()
    val sc = spark.sparkContext
    import spark.implicits._
    /*
    val ylzxTable = args(0)
    val logsTable = args(1)
    val docSimiTable = args(2)
*/
    val ylzxTable = "yilan-total_webpage"
    val logsTable = "t_hbaseSink"
    val docSimiTable = "docsimi_item"

    val ylzxRDD = getYlzxRDD(ylzxTable, sc)
    val ylzxDF = spark.createDataset(ylzxRDD).dropDuplicates("content").drop("content")

    val logsRDD = getLogsRDD(logsTable, sc)
    val logsDS = spark.createDataset(logsRDD).na.drop(Array("userString"))

    val ds1 = logsDS.groupBy("userString", "itemString").agg(sum("value")).withColumnRenamed("sum(value)", "rating")
    //string to number
    val userID = new StringIndexer().setInputCol("userString").setOutputCol("userID").fit(ds1)
    val ds2 = userID.transform(ds1)
    val urlID = new StringIndexer().setInputCol("itemString").setOutputCol("urlID").fit(ds2)
    val ds3 = urlID.transform(ds2)

    val ds4 = ds3.withColumn("userID", ds3("userID").cast("long")).
      withColumn("urlID", ds3("urlID").cast("long")).
      withColumn("rating", ds3("rating").cast("double"))

    //Min-Max Normalization[-1,1]
    val minMax = ds4.agg(max("rating"), min("rating")).withColumnRenamed("max(rating)", "max").withColumnRenamed("min(rating)", "min")
    val maxValue = minMax.select("max").rdd.map { case Row(d: Double) => d }.first()
    val minValue = minMax.select("min").rdd.map { case Row(d: Double) => d }.first
    //limit the values to 4 digit
    val ds5 = ds4.withColumn("norm", bround((((ds4("rating") - minValue) / (maxValue - minValue)) * 2 - 1), 4))

    //RDD to RowRDD
    val rdd1 = ds5.select("userID", "urlID", "norm").rdd.
      map { case Row(user: Long, item: Long, value: Double) => MatrixEntry(user, item, value) }

    //calculate similarities
    val ratings = new CoordinateMatrix(rdd1)
    val itemSimi = ratings.toRowMatrix.columnSimilarities(0.1)
    val itemSimiRdd = itemSimi.entries.map(f => ItemSimi(f.i, f.j, f.value)).
      union(itemSimi.entries.map(f => ItemSimi(f.j, f.i, f.value)))

    val itemSimiDF = spark.createDataset(itemSimiRdd)
    val itemLab = ds5.select("itemString", "urlID").dropDuplicates
    val itemid1Lab = itemLab.withColumnRenamed("urlID","itemid1")
    val itemid2Lab = itemLab.withColumnRenamed("urlID","itemid2").withColumnRenamed("itemString", "url2id")



    sc.stop()
    spark.stop()
  }
}
