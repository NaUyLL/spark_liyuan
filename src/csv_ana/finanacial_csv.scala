import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.expressions.Window


object finanacial_csv {
  def main(args: Array[String]): Unit = {

    val spark = SparkSession.builder().master("spark://spark:7077").appName("Financial_csv").getOrCreate()

    //读取本地文件
    val f_csv=spark.read.option("header", "true").option("multiLine", true).csv("/usr/local/spark/resources/data/Financial_Sample.csv")

    val fcol = f_csv.columns

    // 用于处理表头，包括去除括号及里面的中文，去除中间的空格转为"_"，去除两端的空格
    def columnName_deal(S: String)={
      var S_deal = S.trim
      val a = S_deal.indexOf("(")
      val b = S_deal.indexOf("（")
      if (a != -1){
        if (b<a && b != -1)
        {
          S_deal = S_deal.substring(0,b).trim
        }
        else{
          S_deal = S_deal.substring(0,a).trim
        }
      }
      else{
        if (b != -1)
        {
          S_deal = S_deal.substring(0,b).trim
        }
        else{
          S_deal = S_deal.trim
        }
      }
      S_deal.replace(" ", "_")
    }

    // 用于将所有金钱类列转换为string
    def col2Float(S: String) = {
      var S_deal = S.trim() // 去除首尾空格
      if (S_deal.charAt(0).equals('$')){ // 去除"$"符号
        S_deal = S_deal.substring(1, S_deal.length)
      }
      S_deal = S_deal.trim()
      if (S_deal.indexOf("(") != -1){ // 部分数据呈“$(xxx)”格式，需要清洗括号
        S_deal = S_deal.substring(S_deal.indexOf("(")+1, S_deal.indexOf(")"))
      }
      if (S_deal.charAt(0).equals('-')){ // 部分数据呈“$-”格式，需要转为0
        "0.0"
      }
      else{ // 将xxx,xxx.00中的“,”去除
        S_deal.replace(",","").toFloat.formatted("%.2f")
      }
    }

    val c2f = udf(col2Float _) // 注册为UDF函数，方便DF调用
    val str_trim = udf((str: String) => str.trim()) // 用于消除普通数据的前后空格的UDF
    val dd = udf((str: String) => str.trim.replace("/","-")) // 用于将日期xxxx/xx/xx转为xxxx-xx-xx

    val float_2f = udf((str: String) => str.toFloat.formatted("%.2f")) // 将过长小数规范到2位

    var newf_csv = f_csv.toDF(fcol.map(columnName_deal(_)): _*) // 处理表头

    // 处理日期
    newf_csv = newf_csv.withColumn("Date", dd(col("Date")))

    val newfcol = newf_csv.columns // 将所有列都处理一下前后空格问题
    newfcol.map(column =>{
      newf_csv = newf_csv.withColumn(column, str_trim(col(column)))
    })

//    // 存储到本地table
//    newf_csv.write
//      .mode(SaveMode.Append)
//      .partitionBy("Year", "Month_Number")
//      .saveAsTable("financial")
//
//    // 获取需要的列数据
//    var pre_fcsv = spark.sql(
//      "select Country, Product, Units_Sold, Sales, COGS, Profit, Year, Month_Number as Month from financial "
//    )

    var pre_fcsv = newf_csv.select(col("Country"), col("Product"), col("Units_Sold"),
      col("Sales"), col("COGS"), col("Profit"), col("Year"), col("Month_Number").as("Month"))

    // 处理钱类数据
    pre_fcsv = pre_fcsv.withColumn("Sales", c2f(col("Sales")))
      .withColumn("COGS", c2f(col("COGS")))
      .withColumn("Profit", c2f(col("Profit")))

    // 合并年月，使得后续group更方便
    pre_fcsv = pre_fcsv.withColumn("YM", concat_ws("-",col("Year"), col("Month")))
      .drop("Year","Month")

    // 月度销售额top3国家
    val top3_sales_country = pre_fcsv.groupBy("Country", "YM")
        .agg(sum("Sales") as "Sales_c")
    val win1 = Window.partitionBy("YM").orderBy(- col("Sales_c"))
    val c_sql1 = top3_sales_country.withColumn("top", rank() over win1)
      .filter("top <= 3").withColumn("Sales_c", float_2f(col("Sales_c")))
    c_sql1.show()

    // 月度利润率top3国家
    val top3_profitraio_country = pre_fcsv.groupBy("Country", "YM")
        .agg(sum("COGS") as "COGS_c", sum("Profit") as "Profit_c")
        .withColumn("Profit_ratio", col("Profit_c")/col("COGS_c"))
    val win2 = Window.partitionBy("YM").orderBy(- col("Profit_ratio"))
    val c_sql2 = top3_profitraio_country.withColumn("top", rank() over win2)
      .filter("top <= 3").select(col("Country"), col("YM"), float_2f(col("Profit_ratio")), col("top"))//.withColumn("Profit_ratio", float_2f(col("Sales_c")))
    c_sql2.show()

    // 月度畅销top2商品
    val top2_sold_product = pre_fcsv.groupBy("Product", "YM")
      .agg(sum("Units_Sold") as "Units_Sold_p")
    val win3 = Window.partitionBy("YM").orderBy(- col("Units_Sold_p"))
    val p_sql1 = top2_sold_product.withColumn("top", rank() over win3)
      .filter("top <= 2")
    p_sql1.show()

    // 月度利润额最低top2商品
    val top2_sales_loe_product = pre_fcsv.groupBy("Product", "YM")
      .agg(sum("Profit") as "Profit_p")
    val win4 = Window.partitionBy("YM").orderBy(col("Profit_p"))
    val p_sql2 = top2_sales_loe_product.withColumn("top", rank() over win4)
      .filter("top <= 2").withColumn("Profit_p", float_2f(col("Profit_p")))
    p_sql2.show()

    spark.close()

  }

}
