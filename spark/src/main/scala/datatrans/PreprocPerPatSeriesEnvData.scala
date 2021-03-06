package datatrans

import java.util.concurrent.atomic.AtomicInteger

import scala.ref.SoftReference
import datatrans.Utils._
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.{DataFrame, SparkSession}
import play.api.libs.json._
import org.joda.time._
import play.api.libs.json.Json.JsValueWrapper
import scopt._

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

case class PreprocPerPatSeriesEnvDataConfig(
                   patient_dimension : Option[String] = None,
                   patient_num_list : Option[Seq[String]] = None,
                   input_directory : String = "",
                   time_series : String = "",
                   environmental_data : Option[String] = None,
                   output_prefix : String = "",
                   start_date : DateTime = DateTime.now(),
                   end_date : DateTime = DateTime.now(),
                   output_format : String = "json",
                   geo_coordinates : Boolean = false,
                   sequential : Boolean = false,
                   date_offsets : Seq[Int]= -7 to 7,
                   indices : Seq[String] = Seq("o3", "pm25"),
                   statistics : Seq[String] = Seq("avg", "max", "min", "stddev")
                 )

object PreprocPerPatSeriesEnvData {
  val cache: mutable.Map[String, SoftReference[Seq[DataFrame]]] = scala.collection.mutable.Map[String, SoftReference[Seq[DataFrame]]]()

  def loadEnvData(config : PreprocPerPatSeriesEnvDataConfig, spark: SparkSession, coors : Seq[(Int, (Int, Int))], names : Seq[String]) : Map[String, Map[String, Double]] = {

    val dfs = coors.flatMap {
      case (year, (row, col)) =>
        val filename = f"${config.input_directory}/${config.environmental_data.get}/cmaq$year/C$col%03dR$row%03dDaily.csv"

        def loadEnvDataFrame(filename: String) = {
          val df = spark.read.format("csv").option("header", value = true).load(filename)
          if (names.forall(x => df.columns.contains(x))) {
            cache(filename) = new SoftReference(Seq(df))
            println("SoftReference created for " + filename)
            Seq(df)
          } else {
            print(f"$filename doesn't contain all required columns")
            Seq()
          }
        }

        cache.get(filename) match {
          case None =>
            loadEnvDataFrame(filename)
          case Some(x) =>
            x.get.getOrElse {
              println("SoftReference has already be garbage collected " + filename)
              loadEnvDataFrame(filename)
            }
        }

    }

    if (dfs.nonEmpty) {
      val df = dfs.reduce((a,b) => a.union(b))
      import spark.implicits._
      df.map(row => (row.getString(0), row.getValuesMap[String](names).mapValues(x => x.toDouble))).collect.toMap

    } else
      Map.empty

  }



  def loadDailyEnvData(config : PreprocPerPatSeriesEnvDataConfig, lat : Double, lon : Double, start_date : DateTime, env_data : Map[String, Map[String, Double]], coors : Map[Int, (Int, Int)], i : Int, names : Seq[String]) : JsObject = {
    var env = Json.obj()

    for(ioff <- config.date_offsets) {
      val curr_date = start_date.plusDays(ioff)
      val str = curr_date.toString(DATE_FORMAT)

      env_data.get(str) match {
        case Some(data) =>
          env ++= Json.obj("start_date" -> str)
          env ++= Json.obj(names.flatMap( name => {
            val num = data(name)
            // println("num = " + num)
            if (!num.isNaN)
              Seq(name + "_day" + ioff -> (num: JsValueWrapper))
            else
              Seq()

          }): _*)
        case None =>
      }

      if (config.geo_coordinates) {
        val year = curr_date.year.get
        coors.get(year) match {
          case Some((row, col)) =>
            env ++= Json.obj("row_day" + ioff -> row, "col_day" + ioff -> col, "year_day" + ioff -> year)
          case None =>
        }
      }
    }

    if(config.geo_coordinates)
      env ++= Json.obj(
        "lat" -> lat,
        "lon" -> lon
      )
    env ++= Json.obj("start_date" -> start_date.toString(DATE_FORMAT))

    env
  }

  def proc_pid(config : PreprocPerPatSeriesEnvDataConfig, spark: SparkSession, p:String): Unit =
    time {

      val hc = spark.sparkContext.hadoopConfiguration

      val input_file = f"${config.input_directory}/${config.time_series}/$p"
      val input_file_path = new Path(input_file)
      val input_file_file_system = input_file_path.getFileSystem(hc)

      val output_file = config.output_prefix + p
      val output_file_path = new Path(output_file)
      val output_file_file_system = output_file_path.getFileSystem(hc)

      if(output_file_file_system.exists(output_file_path)) {
        println(output_file + " exists")
      } else {

        if(!input_file_file_system.exists(input_file_path)) {
          println("json not found, skipped " + p)
        } else {
          println("loading json from " + input_file)
          val input_file_input_stream = input_file_file_system.open(input_file_path)

          val jsvalue = Json.parse(input_file_input_stream)
          input_file_input_stream.close()
          val data = ListBuffer[JsObject]() // a list of concept, start_time

          jsvalue \ "lat" match {
            case JsUndefined() =>
              println("lat doesn't exists")
            case JsDefined(latitutestr) =>
              val lat = latitutestr.as[Double]
              jsvalue \ "lon" match {
                case JsUndefined() =>
                  println("lon doesn't exists")
                case JsDefined(lons) =>
                  val lon = lons.as[Double]
                  val coors = (config.start_date.year.get to config.end_date.minusDays(1).year.get).flatMap(year => {
                    latlon2rowcol(lat, lon, year) match {
                      case Some((row, col)) =>
                        Seq((year, (row, col)))
                      case _ =>
                        Seq()
                    }
                  })
                  val names = for (i <- config.statistics; j <- config.indices) yield f"${j}_$i"

                  val env_data = loadEnvData(config, spark, coors, names)

                  for (i <- 0 until Days.daysBetween(config.start_date, config.end_date).getDays) {
                    data += loadDailyEnvData(config, lat, lon, config.start_date.plusDays(i), env_data, coors.toMap, i, names)
                  }

                  val json = config.output_format match {
                    case "json" =>
                      data.map(obj => Json.stringify (obj)+"\n").mkString("")
                    case "csv" =>
                      val headers = data.map(obj => obj.keys).fold(Set.empty[String])((keys1, keys2) => keys1.union(keys2)).toSeq
                      val rows = data.map(obj => headers.map(col => obj \ col match {
                        case JsDefined(a) =>
                          a.toString
                        case _ =>
                          ""
                      }).mkString("!")).mkString("\n")
                      headers.mkString("!") + "\n" + rows
                    case _ =>
                      throw new UnsupportedOperationException("unsupported output format " + config.output_format)
                  }
                  println("writing output to " + output_file)
                  writeToFile(hc, output_file, json)

              }

          }



        }
      }
    }


  def main(args: Array[String]) {
    val parser = new OptionParser[PreprocPerPatSeriesEnvDataConfig]("series_to_vector") {
      head("series_to_vector")
      opt[String]("patient_dimension").action((x,c) => c.copy(patient_dimension = Some(x)))
      opt[Seq[String]]("patient_num_list").action((x,c) => c.copy(patient_num_list = Some(x)))
      opt[String]("input_directory").required.action((x,c) => c.copy(input_directory = x))
      opt[String]("time_series").required.action((x,c) => c.copy(time_series = x))
      opt[String]("environmental_data").required.action((x,c) => c.copy(environmental_data = Some(x)))
      opt[String]("output_prefix").required.action((x,c) => c.copy(output_prefix = x))
      opt[String]("start_date").required.action((x,c) => c.copy(start_date = DateTime.parse(x)))
      opt[String]("end_date").required.action((x,c) => c.copy(end_date = DateTime.parse(x)))
      opt[String]("output_format").action((x,c) => c.copy(output_format = x))
      opt[Unit]("coordinates").action((_,c) => c.copy(geo_coordinates = true))
      opt[Unit]("sequential").action((_,c) => c.copy(sequential = true))
      opt[Seq[Int]]("date_offsets").action((x,c) => c.copy(date_offsets = x))
      opt[Seq[String]]("query").action((x,c) => c.copy(indices = x))
      opt[Seq[String]]("statistics").action((x,c) => c.copy(statistics = x))
    }

    val spark = SparkSession.builder().appName("datatrans preproc").getOrCreate()

    spark.sparkContext.setLogLevel("WARN")

    // For implicit conversions like converting RDDs to DataFrames
    import spark.implicits._

    parser.parse(args, PreprocPerPatSeriesEnvDataConfig()) match {
      case Some(config) =>

        time {

          def proc_pid2(p : String): Unit =
            proc_pid(config, spark, p)

          val patl0 = config.patient_num_list match {
            case Some(pnl) =>
              pnl
            case None =>
              config.patient_dimension match {
                case Some(pdif) =>
                  println("loading patient_dimension from " + pdif)
                  val pddf0 = spark.read.format("csv").option("header", value = true).load(config.input_directory + "/" + pdif)

                  pddf0.select("patient_num").map(r => r.getString(0)).collect.toList

                case None =>
                  Seq()
              }

          }

          val patl = if (config.sequential) patl0 else patl0.par

          val count = new AtomicInteger(0)
          val n = patl.size
          patl.foreach(pid => {
            println("processing " + count.incrementAndGet + " / " + n + " " + pid)
            proc_pid2(pid)
          })

        }
      case None =>
    }


  spark.stop()


  }
}
