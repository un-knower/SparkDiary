package com.evayInfo.Inglory.Project.sentiment.dataClean

import com.evayInfo.Inglory.util.mysqlUtil
import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkConf
import org.apache.spark.sql._
import org.apache.spark.sql.functions._

/**
  * Created by sunlu on 17/7/21.
  *
  * `DA_BBSARTICLE`文章表：
  * `ID`：文章ID
  * `TITLE`：标题
  * `CONTENT`：内容
  * `AUTHOR`：作者
  * `TIME`：发布时间
  * `CLICKNUM`：点击数
  * `REPLY`：回复数
  * `KEYWORD`：主题
  * `BZ`：备注
  * `TASKID`
  *
  *
  * 修改为：
  *
  * 4) `DA_BBSARTICLE`文章表中获取的数据为：
  * `ID`（文章ID）
  * `TITLE`（标题）
  * `CONTENT`（内容）
  * `APPC`：带样式的正文
  * `URL`：源网页地址
  * `TIME`（发布时间）
  * `KEYWORD`（主题）
  * 新增一列`SOURCE`（来源）列：来源为`LUNTAN`
  * 新增一列`IS_COMMENT`：是否是评论, 0：否 1：是
  *
  *
  * ========================
  *
  * *
  * `DA_BBSCOMMENT`评论表：
  * `ID`：评论ID
  * `ARTICLEID`：对应文章表中的文章id
  * `JSUSERNAME`：评论作者
  * `JSRESTIME`：评论时间
  * `FLOORID`：楼
  * `BBSCONTENT`：评论的内容
  *
  *
  * 修改为
  *
  * 5) `DA_BBSCOMMENT`评论表中获取的数据为：
  * `ID`（评论ID）
  * `ARTICLEID`（对应文章表中的文章id）
  * `TITLE`（标题）：通过`ARTICLEID`从`DA_BBSARTICLE`表中`TITLE`列获取
  * `JSRESTIME`（评论时间）
  * 对`BBSCONTENT`（评论的内容）进行数据清洗后结果
  * 新增一列`KEYWORD`（主题）：通过`ARTICLEID`从`DA_BBSARTICLE`表中`KEYWORD`列获取。
  * 新增一列`SOURCE`（来源）列：来源为`LUNTAN`
  * 新增一列`IS_COMMENT`：是否是评论, 0：否 1：是
  *
  *
  *
  *
  */
object dc_luntan {
  def SetLogger = {
    Logger.getLogger("org").setLevel(Level.OFF)
    Logger.getLogger("com").setLevel(Level.OFF)
    System.setProperty("spark.ui.showConsoleProgress", "false")
    Logger.getRootLogger().setLevel(Level.OFF)
  }

  def main(args: Array[String]) {
    SetLogger

    val conf = new SparkConf().setAppName(s"dc_luntan").setMaster("local[*]").set("spark.executor.memory", "2g")
    val spark = SparkSession.builder().config(conf).getOrCreate()
    val sc = spark.sparkContext

    //    val url = "jdbc:mysql://localhost:3306/bbs"
    val url = "jdbc:mysql://localhost:3306/bbs?useUnicode=true&characterEncoding=UTF-8&" +
      "useJDBCCompliantTimezoneShift=true&useLegacyDatetimeCode=false&serverTimezone=UTC"
    val user = "root"
    val password = "root"
    // get DA_BBSARTICLE
    val df_a = mysqlUtil.getMysqlData(spark, url, user, password, "DA_BBSARTICLE")
    // get DA_BBSCOMMENT
    val df_c = mysqlUtil.getMysqlData(spark, url, user, password, "DA_BBSCOMMENT")
    // select columns
    val df_a_1 = df_a.select("ID", "TITLE", "CONTENT", "APPC", "URL", "TIME", "KEYWORD").withColumn("ARTICLEID", col("ID"))
    val df_c_1 = df_c.select("ID", "ARTICLEID", "BBSCONTENT", "JSRESTIME")
    // `KEYWORD`和`TITLE`通过`ARTICLEID`从`DA_BBSARTICLE`表中`KEYWORD`和`TITLE`列获取。
    val keyLib = df_a_1.select("ARTICLEID", "TITLE", "KEYWORD", "URL")
    val df_c_2 = df_c_1.join(keyLib, Seq("ARTICLEID"), "left").withColumn("APPC", lit(null))
    val df_a_2 = df_a_1.withColumn("ARTICLEID", lit(null))

    // add IS_COMMENT column
    val df_a_3 = df_a_2.withColumn("IS_COMMENT", lit(0)).withColumn("SOURCE", lit("LUNTAN"))
    val df_c_3 = df_c_2.withColumn("IS_COMMENT", lit(1)).withColumn("SOURCE", lit("LUNTAN"))


    // change all columns name
    val colRenamed = Seq("articleId", "glArticleId", "title", "content", "keyword", "time", "is_comment", "source", "sourceUrl", "contentPre")
    val df_a_4 = df_a_3.select("ID", "ARTICLEID", "TITLE", "APPC", "KEYWORD", "TIME", "IS_COMMENT", "SOURCE", "URL", "CONTENT").toDF(colRenamed: _*)
    val df_c_4 = df_c_3.select("ID", "ARTICLEID", "TITLE", "APPC", "KEYWORD", "JSRESTIME", "IS_COMMENT", "SOURCE", "URL", "BBSCONTENT") toDF (colRenamed: _*)

    val df = df_a_4.union(df_c_4).na.drop(Array("contentPre")).filter(length(col("contentPre")) >= 1)

    df.printSchema()


    /*
    root
 |-- articleId: string (nullable = true)
 |-- glArticleId: string (nullable = true)
 |-- title: string (nullable = true)
 |-- content: string (nullable = true)
 |-- keyword: string (nullable = true)
 |-- time: string (nullable = true)
 |-- is_comment: integer (nullable = false)
 |-- source: string (nullable = false)
 |-- sourceUrl: string (nullable = true)
 |-- contentPre: string (nullable = true)
     */
    println("数据总数为：" + df.count)
    println("除重后数据总数为：" + df.dropDuplicates().count)
    println("articleId除重后数据总数为：" + df.dropDuplicates(Array("articleId")).count)

    sc.stop()
    spark.stop()

  }

  /*
getLuntanData：获取清洗后的论坛数据
*/
  def getLuntanData(spark: SparkSession, url: String, user: String, password: String,
                    articalTable: String, commentTable: String): DataFrame = {
    // get DA_BBSARTICLE
    val df_a = mysqlUtil.getMysqlData(spark, url, user, password, articalTable)
    // get DA_BBSCOMMENT
    val df_c = mysqlUtil.getMysqlData(spark, url, user, password, commentTable)
    // select columns
    val df_a_1 = df_a.select("ID", "TITLE", "CONTENT", "APPC", "URL", "TIME", "KEYWORD").withColumn("ARTICLEID", col("ID"))
    val df_c_1 = df_c.select("ID", "ARTICLEID", "BBSCONTENT", "JSRESTIME")
    // `KEYWORD`和`TITLE`通过`ARTICLEID`从`DA_BBSARTICLE`表中`KEYWORD`和`TITLE`列获取。
    val keyLib = df_a_1.select("ARTICLEID", "TITLE", "KEYWORD", "URL")
    val df_c_2 = df_c_1.join(keyLib, Seq("ARTICLEID"), "left").withColumn("APPC", lit(null))
    val df_a_2 = df_a_1.withColumn("ARTICLEID", lit(null))

    // add IS_COMMENT column
    val df_a_3 = df_a_2.withColumn("IS_COMMENT", lit(0)).withColumn("SOURCE", lit("LUNTAN"))
    val df_c_3 = df_c_2.withColumn("IS_COMMENT", lit(1)).withColumn("SOURCE", lit("LUNTAN"))


    // change all columns name
    val colRenamed = Seq("articleId", "glArticleId", "title", "content", "keyword", "time", "is_comment", "source", "sourceUrl", "contentPre")
    val df_a_4 = df_a_3.select("ID", "ARTICLEID", "TITLE", "APPC", "KEYWORD", "TIME", "IS_COMMENT", "SOURCE", "URL", "CONTENT").toDF(colRenamed: _*)
    val df_c_4 = df_c_3.select("ID", "ARTICLEID", "TITLE", "APPC", "KEYWORD", "JSRESTIME", "IS_COMMENT", "SOURCE", "URL", "BBSCONTENT") toDF (colRenamed: _*)

    val df = df_a_4.union(df_c_4).na.drop(Array("contentPre")).filter(length(col("contentPre")) >= 1)
    df

  }

}
