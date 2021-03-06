package com.evayInfo.Inglory.Project.DocsSimilarity

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase.client.{HBaseAdmin, Put}
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.mapreduce.TableOutputFormat
import org.apache.hadoop.hbase.util.Bytes
import org.apache.hadoop.hbase.{HBaseConfiguration, HColumnDescriptor, HTableDescriptor, TableName}
import org.apache.hadoop.io.Text
import org.apache.spark.SparkConf
import org.apache.spark.ml.feature.{BucketedRandomProjectionLSH, CountVectorizer, CountVectorizerModel, IDF}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions._

/**
 * Created by sunlu on 17/8/31.
 */
object DocsimiEuclidean {
  def main(args: Array[String]) {

    DocsimiUtil.SetLogger

    val sparkConf = new SparkConf().setAppName(s"DocsimiEuclidean") //.setMaster("local[*]").set("spark.executor.memory", "2g")
    val spark = SparkSession.builder().config(sparkConf).getOrCreate()
    val sc = spark.sparkContext
    import spark.implicits._

    val ylzxTable = args(0)
    val docSimiTable = args(1)
    /*
    val ylzxTable =  "yilan-total_webpage"
    val docSimiTable = "docsimi_euclidean"
*/

    val ylzxRDD = DocsimiUtil.getYlzxSegRDD(ylzxTable, 1, sc)
    val ylzxDS = spark.createDataset(ylzxRDD) //.randomSplit(Array(0.01, 0.99))(0)

    println("ylzxDS的数据量为：" + ylzxDS.count())
    //    ylzxDS的数据量为：95256

    val vocabSize: Int = 20000

    val vocabModel: CountVectorizerModel = new CountVectorizer().
      setInputCol("segWords").
      setOutputCol("features").
      setVocabSize(vocabSize).
      setMinDF(2).
      fit(ylzxDS)

    val docTermFreqs = vocabModel.transform(ylzxDS)
    //    docTermFreqs.cache()

    val idf = new IDF().setInputCol("features").setOutputCol("tfidfVec")
    val idfModel = idf.fit(docTermFreqs)
    val tfidfDF = idfModel.transform(docTermFreqs).select("id", "tfidfVec")
    //    tfidfDF.cache()
    val brp = new BucketedRandomProjectionLSH().
      setBucketLength(2.0).
      setNumHashTables(3).
      setInputCol("tfidfVec").
      setOutputCol("brpVec")

    val brpModel = brp.fit(tfidfDF)
    // Feature Transformation
    val brpTransformed = brpModel.transform(tfidfDF)
    val simiDF = brpModel.approxSimilarityJoin(brpTransformed, brpTransformed, 2.5)

    val colRenamed1 = Seq("doc1Id", "doc2Id", "distCol")
    val simiDF2 = simiDF.select("datasetA.id", "datasetB.id", "distCol").toDF(colRenamed1: _*).
      filter($"doc1Id" =!= $"doc2Id")

    val doc2IdLab = docTermFreqs.select("id", "urlID", "title", "label", "websitename", "time").
      withColumnRenamed("id", "doc2Id").withColumnRenamed("urlID", "doc2")
    val doc1IdLab = docTermFreqs.select("id", "urlID").
      withColumnRenamed("id", "doc1Id").withColumnRenamed("urlID", "doc1")

    val joinedDF = simiDF2.join(doc1IdLab, Seq("doc1Id"), "left").na.drop().
      join(doc2IdLab, Seq("doc2Id"), "left").na.drop()

    val colRenamed2 = Seq("doc1Id", "doc1", "doc2Id", "doc2", "doc2_title",
      "doc2_label", "doc2_websitename", "doc2_time", "distCol")
    val renamedDF = joinedDF.select("doc1Id", "doc1", "doc2Id", "doc2", "title", "label", "websitename", "time", "distCol").
      toDF(colRenamed2: _*).
      filter($"distCol" > 0)
    //对dataframe进行分组排序，并取每组的前5个
    val w = Window.partitionBy("doc1Id").orderBy(col("distCol").asc)
    val sortedDF = renamedDF.withColumn("rn", row_number.over(w)).where(col("rn") <= 5)

    val resultDF = sortedDF.select("doc1", "doc2", "distCol", "rn", "doc2_title", "doc2_label", "doc2_time", "doc2_websitename")

    val hbaseConf = HBaseConfiguration.create() //在HBaseConfiguration设置可以将扫描限制到部分列，以及限制扫描的时间范围
    //设置查询的表名
    //    hbaseConf.set(TableInputFormat.INPUT_TABLE, ylzxTable) //设置输入表名

    /*
    //如果outputTable存在则不做任何操作，如果HBASE表不存在则新建表
    val hadmin = new HBaseAdmin(hbaseConf)
    if (!hadmin.isTableAvailable(docSimiTable)) {
      print("Table Not Exists! Create Table")
      val tableDesc = new HTableDescriptor(TableName.valueOf(docSimiTable))
      tableDesc.addFamily(new HColumnDescriptor("info".getBytes()))
//      tableDesc.addFamily(new HColumnDescriptor("f".getBytes()))
      hadmin.createTable(tableDesc)
    }else{
      print("Table  Exists!  not Create Table")
    }
*/

    //如果outputTable表存在，则删除表；如果不存在则新建表。=> START
    val hAdmin = new HBaseAdmin(hbaseConf)
    if (hAdmin.tableExists(docSimiTable)) {
      hAdmin.disableTable(docSimiTable)
      hAdmin.deleteTable(docSimiTable)
    }
    //    val htd = new HTableDescriptor(outputTable)
    val htd = new HTableDescriptor(TableName.valueOf(docSimiTable))
    htd.addFamily(new HColumnDescriptor("info".getBytes()))
    hAdmin.createTable(htd)
    //如果outputTable表存在，则删除表；如果不存在则新建表。=> OVER

    //指定输出格式和输出表名
    hbaseConf.set(TableOutputFormat.OUTPUT_TABLE, docSimiTable) //设置输出表名


    //    val table = new HTable(hbaseConf,docSimiTable)
    //    hbaseConf.set(TableOutputFormat.OUTPUT_TABLE,docSimiTable)

    val jobConf = new Configuration(hbaseConf)
    jobConf.set("mapreduce.job.outputformat.class", classOf[TableOutputFormat[Text]].getName)

    resultDF.rdd.map(row => (row(0), row(1), row(2), row(3), row(4), row(5), row(6), row(7))).
      map { x => {
        //("doc1", "doc2", "distCol", "rn", "doc2_title", "doc2_label", "doc2_time", "doc2_websitename")
        val paste = x._1.toString + "::score=" + x._4.toString
        val key = Bytes.toBytes(paste)
        val put = new Put(key)
        put.add(Bytes.toBytes("info"), Bytes.toBytes("id"), Bytes.toBytes(x._1.toString)) //doc1
        put.add(Bytes.toBytes("info"), Bytes.toBytes("simsID"), Bytes.toBytes(x._2.toString)) //doc2
        put.add(Bytes.toBytes("info"), Bytes.toBytes("simsScore"), Bytes.toBytes(x._3.toString)) //value
        put.add(Bytes.toBytes("info"), Bytes.toBytes("level"), Bytes.toBytes(x._4.toString)) //rn
        put.add(Bytes.toBytes("info"), Bytes.toBytes("t"), Bytes.toBytes(x._5.toString)) //title2
        put.add(Bytes.toBytes("info"), Bytes.toBytes("manuallabel"), Bytes.toBytes(x._6.toString)) //label2
        put.add(Bytes.toBytes("info"), Bytes.toBytes("mod"), Bytes.toBytes(x._7.toString)) //time2
        put.add(Bytes.toBytes("info"), Bytes.toBytes("websitename"), Bytes.toBytes(x._8.toString)) //websitename2

        (new ImmutableBytesWritable, put)
      }
      }.saveAsNewAPIHadoopDataset(jobConf)


    sc.stop()
    spark.stop()
  }
}
