package com.evayInfo.Inglory.Project.Recommend

import java.text.SimpleDateFormat
import java.util.Properties

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase.client.{HBaseAdmin, Put, Scan}
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.mapreduce.{TableInputFormat, TableOutputFormat}
import org.apache.hadoop.hbase.protobuf.ProtobufUtil
import org.apache.hadoop.hbase.util.{Base64, Bytes}
import org.apache.hadoop.hbase.{HBaseConfiguration, HColumnDescriptor, HTableDescriptor, TableName}
import org.apache.hadoop.io.Text
import org.apache.spark.ml.feature.StringIndexer
import org.apache.spark.mllib.linalg.distributed.{CoordinateMatrix, MatrixEntry}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.{SparkConf, SparkContext}

/**
 * Created by sunlu on 17/9/15.
 * 使用
 * 用户关注标签信息：`YLZX_NRGL_OPER_CATEGORY`表中的 `OPERATOR_ID`列和`CATEGORY_NAME`列，
 * 用户订阅网站栏目信息：`YLZX_NRGL_MYSUB_WEBSITE_COL` 表中的`OPERATOR_ID`和`COLUMN_ID`列
用户浏览日志
用户热门标签点击日志
=> 计算用户相似性。
 做基于用户的推荐
 */
object userModelV2 {

  case class UserSimi(userId1: Long, userId2: Long, similar: Double)

  def main(args: Array[String]) {
    RecomUtil.SetLogger

    val SparkConf = new SparkConf().setAppName(s"userModelV2").setMaster("local[*]").set("spark.executor.memory", "2g")
    val spark = SparkSession.builder().config(SparkConf).getOrCreate()
    val sc = spark.sparkContext
    import spark.implicits._

    /*
    val ylzxTable = args(0)
    val logsTable = args(1)
    val outputTable = args(2)
*/
    val ylzxTable = "yilan-total_webpage"
    val logsTable = "t_hbaseSink"
    val outputTable = "recommender_user"

    //    val url1 = "jdbc:mysql://localhost:3306/ylzx?useUnicode=true&characterEncoding=UTF-8&useJDBCCompliantTimezoneShift=true&useLegacyDatetimeCode=false&serverTimezone=UTC"
    val url1 = "jdbc:mysql://192.168.37.102:3306/ylzx?useUnicode=true&characterEncoding=UTF-8"
    val prop1 = new Properties()
    /*
    prop1.setProperty("driver", "com.mysql.jdbc.Driver")
    prop1.setProperty("user", "root")
    prop1.setProperty("password", "root")
    */
    prop1.setProperty("user", "ylzx")
    prop1.setProperty("password", "ylzx")
    //get data
    val website_df = spark.read.jdbc(url1, "YLZX_NRGL_MYSUB_WEBSITE_COL", prop1)
    val category_df = spark.read.jdbc(url1, "YLZX_NRGL_OPER_CATEGORY", prop1)

    val website_df1 = website_df.select("OPERATOR_ID", "COLUMN_ID").
      withColumnRenamed("COLUMN_ID", "userFeature").
      withColumn("value", lit(1.0)).na.drop().dropDuplicates() //增大网站标签权重
    val category_df1 = category_df.select("OPERATOR_ID", "CATEGORY_NAME").
        withColumnRenamed("CATEGORY_NAME", "userFeature").
        withColumn("value", lit(1.0)).na.drop().dropDuplicates()

    val logsRDD = getLogsRDD(logsTable, sc)
    val logsDS_temp = spark.createDataset(logsRDD).na.drop(Array("userString")).
      groupBy("userString", "itemString").
      agg(sum("value")).
      withColumnRenamed("sum(value)", "rating").drop("value") // "userString", "itemString" ,"rating"

    // 对浏览行为进行标准化
    val a_scaledRange = lit(1) // Range of the scaled variable
    val a_scaledMin = lit(0) // Min value of the scaled variable

    val (aMin, aMax) = logsDS_temp.agg(min($"rating"), max($"rating")).first match {
      case Row(x: Double, y: Double) => (x, y)
    }
    val a_Normalized = ($"rating" - aMin) / (aMax - aMin) // v normalized to (0, 1) range
    val a_Scaled = a_scaledRange * a_Normalized + a_scaledMin
    val logsDS = logsDS_temp.withColumn("vScaled", a_Scaled).
      drop("rating").withColumnRenamed("vScaled", "rating")



    // 用户计算用户相似性的日志，只计算最近一月的访问情况
    /*
    val logsSimiRDD = getLogsSimiRDD(logsTable, sc)
    val logsSimiDS = spark.createDataset(logsSimiRDD).na.drop(Array("userString")).
      groupBy("userString", "itemString").
      agg(sum("value")).
      withColumnRenamed("sum(value)", "rating").drop("value") // "userString", "itemString" ,"rating"
*/
    val logs_df1 = logsDS.withColumnRenamed("userString", "OPERATOR_ID").
      withColumnRenamed("itemString", "userFeature").withColumnRenamed("rating", "value")

    //(userString: String, searchWords: String, searchValue: Double)
    val hotlabelRDD = getHotLabelLogRDD(logsTable, sc)
    val hotlabelDS = spark.createDataset(hotlabelRDD).na.drop(Array("userString")).
      groupBy("userString", "searchWords").
      agg(sum("searchValue")).
      withColumnRenamed("sum(searchValue)", "value").drop("searchValue")
    val hotlabel_df1 = hotlabelDS.withColumnRenamed("userString", "OPERATOR_ID").
      withColumnRenamed("searchWords", "userFeature")
    //    println("hotlabel_df1 is: " + hotlabel_df1.count())//hotlabel_df1 is: 600
    //    hotlabel_df1.select("userFeature").dropDuplicates().show(200,false)


    /*
    对用户网站关注行为、标签关注行为、浏览行为和热门标签点击行为数据分别进行标准化处理
     */
/*
    // 对浏览行为进行呢标准化
    val l_scaledRange = lit(1) // Range of the scaled variable
    val l_scaledMin = lit(0) // Min value of the scaled variable

    val (lMin, lMax) = logs_df1.agg(min($"value"), max($"value")).first match {
      case Row(x: Double, y: Double) => (x, y)
    }
    val lNormalized = ($"value" - lMin) / (lMax - lMin) // v normalized to (0, 1) range
    val lScaled = l_scaledRange * lNormalized + l_scaledMin
    val logs_df2 = logs_df1.withColumn("vScaled", lScaled).
      drop("value").withColumnRenamed("vScaled", "value")
*/
    // 对热门标签点击行为数据进行标准化
    val h_scaledRange = lit(1) // Range of the scaled variable
    val h_scaledMin = lit(1) // Min value of the scaled variable

    val (hMin, hMax) = hotlabel_df1.agg(min($"value"), max($"value")).first match {
      case Row(x: Double, y: Double) => (x, y)
    }
    val hNormalized = ($"value" - hMin) / (hMax - hMin) // v normalized to (0, 1) range
    val hScaled = h_scaledRange * hNormalized + h_scaledMin
    val hotlabel_df2 = hotlabel_df1.withColumn("vScaled", hScaled).
      drop("value").withColumnRenamed("vScaled", "value")

    /*
    数据合并
     */
    val df = website_df1.union(category_df1).union(logs_df1).union(hotlabel_df2).
//    val df = website_df1.union(logs_df1).union(hotlabel_df2).
      groupBy("OPERATOR_ID", "userFeature").
      agg(sum("value")).drop("value").
      withColumnRenamed("sum(value)", "value")


    //string to number
    val userID = new StringIndexer().setInputCol("OPERATOR_ID").setOutputCol("userID").fit(df)
    val df1 = userID.transform(df)
    val urlID = new StringIndexer().setInputCol("userFeature").setOutputCol("featureID").fit(df1)
    val df2 = urlID.transform(df1)

    val df3 = df2.withColumn("userID", df2("userID").cast("long")).
      withColumn("featureID", df2("featureID").cast("long")).
      withColumn("value", df2("value").cast("double"))

    val user1IdLab = df3.select("OPERATOR_ID", "userID").dropDuplicates().
      withColumnRenamed("userID", "user1Id").
      withColumnRenamed("OPERATOR_ID", "user1")
    val user2IdLab = df3.select("OPERATOR_ID", "userID").dropDuplicates().
      withColumnRenamed("userID", "user2Id").
      withColumnRenamed("OPERATOR_ID", "user2")
    //    val featureIdLab = df3.select("userFeature","featureID").dropDuplicates()

    //RDD to RowRDD
    val rdd1 = df3.select("userID", "featureID", "value").rdd.
      map { case Row(user: Long, item: Long, value: Double) => MatrixEntry(user, item, value) }

    //calculate similarities
    val ratings = new CoordinateMatrix(rdd1).transpose()
    val userSimi = ratings.toRowMatrix.columnSimilarities(0.1)
    // user-user similarity
    val userSimiRdd = userSimi.entries.map(f => UserSimi(f.i, f.j, f.value))

    // user1, user1, similarity
    val userSimiDF = userSimiRdd.map { f => (f.userId1, f.userId2, f.similar) }.
      union(userSimiRdd.map { f => (f.userId2, f.userId1, f.similar) }).toDF("user1Id", "user2Id", "userSimi")

    val userSimiDF2 = userSimiDF.join(user1IdLab, Seq("user1Id"), "left").
      join(user2IdLab, Seq("user2Id"), "left").na.drop.select("user1", "user2", "userSimi")

    /*
        val myID = """175786f8-1e74-4d6c-94e9-366cf1649721"""
        userSimiDF2.filter(col("user1").contains("175786f8")).show
        userSimiDF2.filter($"user1" === myID).show
        userSimiDF2.filter($"user1" === myID).orderBy(col("userSimi").desc).show(false)
        userSimiDF2.filter(col("user1").contains("175786f8")).orderBy(col("userSimi").desc).show(false)
        val simUserId = "e5e16dfb-8dfa-4467-aaa2-7c3081fb55bb"
    */


    /*
  基于用户的协同过滤：data frame版本
   */

    // user1, user2, userSimi, userString, itemString, rating
    val userR_1 = userSimiDF2.join(logsDS, userSimiDF2("user1") === logsDS("userString"), "left").
      withColumn("recomValue", col("userSimi") * col("rating")).
      groupBy("user2", "itemString").agg(sum($"recomValue")).drop("recomValue").
      withColumnRenamed("sum(recomValue)", "recomValue")
    // user2, itemString, recomValue

    val logsDS2 = logsDS.select("userString", "itemString").withColumnRenamed("userString", "user2").withColumn("whether", lit(1))
    val userR_2 = userR_1.join(logsDS2, Seq("user2", "itemString"), "left").filter(col("whether").isNull)

    val ylzxRDD = RecomUtil.getYlzxRDD(ylzxTable, 20, sc)
    val ylzxDF = spark.createDataset(ylzxRDD).dropDuplicates("content").drop("content")

    val userR_3 = userR_2.join(ylzxDF, Seq("itemString"), "left").
      select("user2", "itemString", "recomValue", "title", "manuallabel", "time").na.drop()

    /*
        logsDS.filter(col("userString") === simUserId).orderBy(col("rating").desc).show(false)
        userR_3.filter(col("user2") === myID).orderBy(col("recomValue").desc).show(false)
        userR_3.filter(col("user2") === myID).select("title","time").orderBy(col("time").desc).show(200, false)
    */

    //    userR_3.withColumn("temp", lit(1)).groupBy("user2").agg(sum("temp")).show(false)

    //对dataframe进行分组排序，并取每组的前5个
    val w = Window.partitionBy("user2").orderBy(col("recomValue").desc)
    val userR_4 = userR_3.withColumn("rn", row_number.over(w)).where(col("rn") <= 15)

    /*
    val simiItem = "fed8a4c7-8c0a-4f1a-b684-0eb08b9f5fb0"
    val simiItem2 = "e7685b22-451a-434e-8489-832561c770d9"

     get 'yilan-total_webpage','fed8a4c7-8c0a-4f1a-b684-0eb08b9f5fb0'

get 'yilan-total_webpage','e7685b22-451a-434e-8489-832561c770d9'


    logsDS.filter(col("userString") === myID).filter(col("itemString") === simiItem).show(false)
    logsDS2.filter(col("user2") === myID).filter(col("itemString") === simiItem).show(false)
    userR_3.filter(col("user2") === myID).filter(col("itemString") === simiItem).show(false)//null
    userR_4.filter(col("user2") === myID).filter(col("itemString") === simiItem).show(false)//null

    logsDS.filter(col("userString") === myID).filter(col("itemString") === simiItem2).show(false)//null
    logsDS2.filter(col("user2") === myID).filter(col("itemString") === simiItem2).show(false)//null
    userR_3.filter(col("user2") === myID).filter(col("itemString") === simiItem2).show(false)
    userR_4.filter(col("user2") === myID).filter(col("itemString") === simiItem2).show(false)//null
    userR_4.filter(col("user2") === myID).select("title","time").orderBy(col("time").desc).show(200, false)
    userR_4.filter(col("user2") === myID).select("title","time","rn").orderBy(col("rn").asc, col("time").desc).show(200, false)

     val wId = "e5e16dfb-8dfa-4467-aaa2-7c3081fb55bb"
     userR_4.filter(col("user2") === wId).select("title","time","rn").orderBy(col("rn").asc, col("time").desc).show(200, false)

     */

    val userR_5 = userR_4.select("user2", "itemString", "recomValue", "rn", "title", "manuallabel", "time").na.drop.
      withColumn("systime", current_timestamp()).withColumn("systime", date_format($"systime", "yyyy-MM-dd HH:mm:ss"))
    /*
    root
     |-- user2: string (nullable = true)
     |-- itemString: string (nullable = true)
     |-- recomValue: double (nullable = true)
     |-- rn: integer (nullable = true)
     |-- title: string (nullable = true)
     |-- manuallabel: string (nullable = true)
     |-- time: string (nullable = true)
     |-- systime: string (nullable = false)
     */
    val conf = HBaseConfiguration.create() //在HBaseConfiguration设置可以将扫描限制到部分列，以及限制扫描的时间范围

    /*
    如果outputTable表存在，则删除表；如果不存在则新建表。
     */
    val hAdmin = new HBaseAdmin(conf)
    if (hAdmin.tableExists(outputTable)) {
      hAdmin.disableTable(outputTable)
      hAdmin.deleteTable(outputTable)
    }
    //    val htd = new HTableDescriptor(outputTable)
    val htd = new HTableDescriptor(TableName.valueOf(outputTable))
    htd.addFamily(new HColumnDescriptor("info".getBytes()))
    hAdmin.createTable(htd)

    //指定输出格式和输出表名
    conf.set(TableOutputFormat.OUTPUT_TABLE, outputTable) //设置输出表名

    val jobConf = new Configuration(conf)
    jobConf.set("mapreduce.job.outputformat.class", classOf[TableOutputFormat[Text]].getName)

    userR_5.toDF().rdd.map {
      case Row(user2: String, itemString: String, recomValue: Double, rn: Int, title: String, manuallabel: String, time: String, systime: String) =>
        (user2, itemString, recomValue, rn, title, manuallabel, time, systime)
    }.
      map(x => {
        val userString = x._1.toString
        val itemString = x._2.toString
        //保留rating有效数字
        val rating = x._3.toString.toDouble
        val rating2 = f"$rating%1.5f".toString
        val rn = x._4.toString
        val title = if (null != x._5) x._5.toString else ""
        val manuallabel = if (null != x._6) x._6.toString else ""
        val time = if (null != x._7) x._7.toString else ""
        val sysTime = if (null != x._8) x._8.toString else ""
        (userString, itemString, rating2, rn, title, manuallabel, time, sysTime)
      }).filter(_._5.length >= 2).
      map { x => {
        val paste = x._1 + "::score=" + x._4.toString
        val key = Bytes.toBytes(paste)
        val put = new Put(key)
        put.add(Bytes.toBytes("info"), Bytes.toBytes("userID"), Bytes.toBytes(x._1.toString)) //标签的family:qualify,userID
        put.add(Bytes.toBytes("info"), Bytes.toBytes("id"), Bytes.toBytes(x._2.toString)) //id
        put.add(Bytes.toBytes("info"), Bytes.toBytes("rating"), Bytes.toBytes(x._3.toString)) //rating
        put.add(Bytes.toBytes("info"), Bytes.toBytes("rn"), Bytes.toBytes(x._4.toString)) //rn
        put.add(Bytes.toBytes("info"), Bytes.toBytes("title"), Bytes.toBytes(x._5.toString)) //title
        put.add(Bytes.toBytes("info"), Bytes.toBytes("manuallabel"), Bytes.toBytes(x._6.toString)) //manuallabel
        put.add(Bytes.toBytes("info"), Bytes.toBytes("mod"), Bytes.toBytes(x._7.toString)) //mod
        put.add(Bytes.toBytes("info"), Bytes.toBytes("sysTime"), Bytes.toBytes(x._8.toString)) //sysTime

        (new ImmutableBytesWritable, put)
      }
      }.saveAsNewAPIHadoopDataset(jobConf)


    sc.stop()
    spark.stop()
  }

  case class LogView(CREATE_BY_ID: String, CREATE_TIME: Long, REQUEST_URI: String, PARAMS: String)

  case class LogView2(userString: String, itemString: String, CREATE_TIME: Long, value: Double)

  def convertScanToString(scan: Scan) = {
    val proto = ProtobufUtil.toScan(scan)
    Base64.encodeBytes(proto.toByteArray)
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
          //          case x if (x >= UtilTool.getSixMonth() && x < UtilTool.getOneMonth()) => 0.5 * value
          //          case x if (x >= UtilTool.getOneYear() && x < UtilTool.getSixMonth()) => 0.4 * value
          //          case x if (x < UtilTool.getOneYear()) => 0.3 * value
          case _ => 0.0
        }

        //val rating = rValue(time, value)
        LogView2(userString, itemString, time, rating)
      }).filter(_.value > 0)

    hbaseRDD
  }

  def getLogsSimiRDD(logsTable: String, sc: SparkContext): RDD[LogView2] = {

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
          //          case x if (x >= UtilTool.getSixMonth() && x < UtilTool.getOneMonth()) => 0.5 * value
          //          case x if (x >= UtilTool.getOneYear() && x < UtilTool.getSixMonth()) => 0.4 * value
          //          case x if (x < UtilTool.getOneYear()) => 0.3 * value
          case _ => 0.0
        }

        //val rating = rValue(time, value)
        LogView2(userString, itemString, time, rating)
      })

    hbaseRDD
  }

  case class HotLabelSchema(userString: String, searchWords: String, CREATE_TIME: Long, value: Double)

  case class HotLabelSchema2(userString: String, searchWords: String, searchValue: Double)


  def getHotLabelLogRDD(logsTable: String, sc: SparkContext): RDD[HotLabelSchema2] = {
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
      }.filter(x => {
      x.REQUEST_URI.contains("search.do") && x.PARAMS.contains("manuallabel=") && x.CREATE_BY_ID.length >= 5
    }).map(x => {
      val userID = x.CREATE_BY_ID.toString
      //      val reg = """manuallabel=.+(,|})""".r
      //      val reg ="""manuallabel=.+,""".r
      val reg =
        """manuallabel=([\u4e00-\u9fa5]|[a-zA-Z])+""".r
      val searchWord = reg.findFirstIn(x.PARAMS.toString).toString.replace("Some(manuallabel=", "").replace(",)", "").replace("}", "").replace(")", "")
      val time = x.CREATE_TIME
      val value = 1.0
      HotLabelSchema(userID, searchWord, time, value)
    }).filter(x => (x.searchWords.length >= 2 && x.searchWords.length <= 8)).
      filter(!_.searchWords.contains("None")).map(x => {
      val userString = x.userString
      val searchWords = x.searchWords
      val time = x.CREATE_TIME
      val value = x.value

      val rating = time match {
        case x if (x >= UtilTool.get3Dasys()) => 0.9 * value
        case x if (x >= UtilTool.get7Dasys() && x < UtilTool.get3Dasys()) => 0.8 * value
        case x if (x >= UtilTool.getHalfMonth() && x < UtilTool.get7Dasys()) => 0.7 * value
        case x if (x >= UtilTool.getOneMonth() && x < UtilTool.getHalfMonth()) => 0.6 * value
        //        case x if (x >= UtilTool.getSixMonth() && x < UtilTool.getOneMonth()) => 0.5 * value
        //        case x if (x >= UtilTool.getOneYear() && x < UtilTool.getSixMonth()) => 0.4 * value
        //        case x if (x < UtilTool.getOneYear()) => 0.3 * value
        case _ => 0.0
      }

      HotLabelSchema2(userString, searchWords, rating)
    }).filter(_.searchValue > 0)

    hbaseRDD
  }


}
