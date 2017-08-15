package com.evayInfo.Inglory.Project.sentiment.sentimentAnalysis

import com.evayInfo.Inglory.Project.sentiment.dataClean._
import com.evayInfo.Inglory.util.mysqlUtil
import org.ansj.splitWord.analysis.ToAnalysis
import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkConf
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{Row, SparkSession}

/**
 * Created by sunlu on 17/8/15.
 */
object sentimentTrendV2 {

  def SetLogger = {
    Logger.getLogger("org").setLevel(Level.OFF)
    Logger.getLogger("com").setLevel(Level.OFF)
    System.setProperty("spark.ui.showConsoleProgress", "false")
    Logger.getRootLogger().setLevel(Level.OFF)
  }

  def main(args: Array[String]) {

    SetLogger

    val conf = new SparkConf().setAppName(s"sentimentTrendV1").setMaster("local[*]").set("spark.executor.memory", "2g")
    val spark = SparkSession.builder().config(conf).getOrCreate()
    val sc = spark.sparkContext
    import spark.implicits._

    val url1 = "jdbc:mysql://localhost:3306/bbs"
    val user1 = "root"
    val password1 = "root"

    val weiboAtable = "DA_WEIBO"
    val weiboCtable = "DA_WEIBO_COMMENTS"
    val weixinTable = "DA_WEIXIN"
    val luntanAtable = "DA_BBSARTICLE"
    val luntanCtable = "DA_BBSCOMMENT"
    val searchTable = "DA_BAIDUARTICLE"
    val menhuTable = "DA_SEED"
    val blogTable = "DA_BLOG"

    val masterTable = "t_yq_article"
    val slaveTable = "t_yq_content"

    val df_weibo = dc_weibo.getWeiboData(spark, url1, user1, password1, weiboAtable, weiboCtable)
    val df_weixin = dc_weixin.getWeixinData(spark, url1, user1, password1, weixinTable)
    val df_luntan = dc_luntan.getLuntanData(spark, url1, user1, password1, luntanAtable, luntanCtable)
    val df_search = dc_search.getSearchData(spark, url1, user1, password1, searchTable)
    val df_menhu = dc_menhu.getMenhuData(spark, url1, user1, password1, menhuTable)
    val df_bolg = dc_blog.getBlogData(spark, url1, user1, password1, blogTable)
    val id_df = mysqlUtil.getMysqlData(spark, url1, user1, password1, masterTable).select("ARTICLEID")

    val df = df_weibo.union(df_weixin).union(df_luntan).union(df_search).union(df_menhu).union(df_bolg).
      filter(length(col("time")) === 19).filter(length(col("title")) >= 2).filter(length(col("content")) >= 1)
    //      .join(id_df, Seq("ARTICLEID"), "leftanti").na.drop(Array("ARTICLEID")).dropDuplicates()
    println("df的数量为：" + df.count) //df的数量为：29106
    println("df除重后的数量为：" + df.dropDuplicates().count) //df除重后的数量为：29106
    println("df中ARTICLEID列除重后的数量为：" + df.dropDuplicates(Array("ARTICLEID")).count) //df中ARTICLEID列除重后的数量为：29106

    //     val df_1 =  df.filter(length(col("title")) >= 2).filter(length(col("content")) >= 2)
    //    println("df_1的数量为：" + df_1.count)//df_1的数量为：29106

    //获取正类、负类词典。posnegDF在join时使用；posnegList在词过滤时使用。
    val posnegDF = spark.read.format("CSV").option("header", "true").load("data/posneg.csv")
    val posnegList = posnegDF.select("term").dropDuplicates().rdd.map { case Row(term: String) => term }.collect().toList

    //load stopwords file
    val stopwordsFile = "data/Stopwords.dic"
    val stopwords = sc.textFile(stopwordsFile).collect().toList

    //定义UDF
    //分词、停用词过滤、正类、负类词过滤
    val segWorsd = udf((content: String) => {
      ToAnalysis.parse(content).toArray.map(_.toString.split("/")).
        filter(_.length >= 2).map(_ (0)).toList.
        filter(word => word.length >= 1 & !stopwords.contains(word)).filter(word => posnegList.contains(word))
        .toSeq.mkString(" ")
    })

    val df1 = df.filter(length(col("contentPre")) >= 1).select("articleId", "contentPre").
      withColumn("segWords", segWorsd(column("contentPre")))

    val df2 = df1.explode("segWords", "tokens") { segWords: String => segWords.split(" ") }
    //    df2.printSchema()

    val df3 = df2.join(posnegDF, df2("tokens") === posnegDF("term"), "left").na.drop()
    val df4 = df3.groupBy("articleId").agg(sum("weight")).withColumnRenamed("sum(weight)", "score")

    val df5 = df4.join(df, Seq("articleId"), "left").drop("contentPre").
      withColumn("systime", current_timestamp()).withColumn("systime", date_format($"systime", "yyyy-MM-dd HH:mm:ss"))

    //      val mainDF = df5.na.drop(Array("title", "content")).drop("content")
    //      val slaveDF = df5.na.drop(Array("title", "content")).select("articleId", "content")


    val masterDF = df5.drop("content").dropDuplicates(Array("articleId"))
    val slaveDF = df.filter(length(col("content")) >= 1).select("articleId", "content").dropDuplicates(Array("articleId"))

    println("slaveDF表数量为：" + slaveDF.count)
    println("masterDF表数量为：" + masterDF.count)


    // truncate Mysql Table
    //    mysqlUtil.truncateMysql(url2, user2, password2, masterTable)
    //    mysqlUtil.truncateMysql(url2, user2, password2, slaveTable)

    // save Mysql Data
    //    mysqlUtil.saveMysqlData(slaveDF, url1, user1, password1, slaveTable, "append")
    //    mysqlUtil.saveMysqlData(masterDF, url1, user1, password1, masterTable, "append")


    sc.stop()
    spark.stop()

  }

}
