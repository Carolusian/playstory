package models

import java.util.Date
import scala.concurrent.Future
import scala.util.matching.Regex
import play.Logger
import play.api.libs.iteratee.Enumerator
import play.api.libs.json._
import play.api.libs.json.Json._
import play.api.libs.json.util._
import play.modules.reactivemongo.PlayBsonImplicits.JsValueWriter
import reactivemongo.api.{QueryBuilder, QueryOpts}
import reactivemongo.api.SortOrder.Descending
import reactivemongo.bson.handlers.DefaultBSONHandlers._
import reactivemongo.core.commands.LastError
import com.mongodb.casbah.Imports._
import utils.reactivemongo.{QueryBuilder => JsonQueryBuilder, _}
import db.MongoDB

case class Log(
  _id: ObjectId,
  project: String,
  logger: String,
  className: String,
  date: Date,
  file: String,
  location: String,
  line: Long,
  message: String,
  method: String,
  level: String,
  thread: String,
  comments: Seq[Comment] = Nil
) {
  def comment(comment: JsValue) = Log.comment(_id, comment)
  def countByLevel(): List[(String, Double)] = Log.countByLevel(project)
}

object Log extends MongoDB("logs", indexes = Seq("keywords", "level", "date", "project")) with Searchable {

  type LogFromWeb = (String, String, String, Date, String, String,
                     Long, String, String, String, String)

  object json {
    def date(log: JsValue): Option[Date] = (log \ "date" \ "$date").asOpt[Long].map(new Date(_))
    def project(log: JsValue): Option[String] = (log \ "project").asOpt[String]
  }

  def all(max: Int = Config.mongodb.limit): Future[List[JsValue]] = {
    val jsonQuery = JsonQueryBuilder().sort("date" -> Descending)
    JsonQueryHelpers.find(collectAsync, jsonQuery).toList(max)
  }

  def byId(id: ObjectId): Future[Option[JsValue]] = {
    val byId = Json.obj("_id" -> Json.obj("$oid" -> id.toString))
    val jsonQuery = JsonQueryBuilder().query(byId)
    JsonQueryHelpers.find(collectAsync, jsonQuery).headOption
  }

  def byIds(ids: List[ObjectId], max: Int = Config.mongodb.limit): Future[List[JsValue]] = {
    val byEnd = "date" -> Descending
    val matchIds = Json.obj(
      "_id" -> Json.obj(
        "$in" -> JsArray(ids.map(id => Json.obj("$oid" -> id.toString)))
      )
    )
    val jsonQuery = JsonQueryBuilder().query(matchIds).sort(byEnd)
    JsonQueryHelpers.find(collectAsync, jsonQuery).toList
  }

  def search(project: String, fields: List[Regex], max: Int = Config.mongodb.limit): Future[List[JsValue]] = {
    val byProject = Json.obj("project" -> project)
    val jsonQuery = JsonQueryBuilder().query(byKeywords(fields)).sort("date" -> Descending)
    JsonQueryHelpers.find(collectAsync, jsonQuery).toList(max)
  }

  def byProject(project: String, max: Int = Config.mongodb.limit): Future[List[JsValue]] = {
    val byProject = Json.obj("project" -> project)
    val jsonQuery = JsonQueryBuilder().query(byProject).sort("date" -> Descending)
    JsonQueryHelpers.find(collectAsync, jsonQuery).toList(max)
  }

  def byLevel(level: String, projectOpt: Option[String] = None, max: Int = Config.mongodb.limit): Future[List[JsValue]] = {
    val byLevel = Json.obj("level" -> level.toUpperCase)
    val byProject = projectOpt.map { project =>
      Json.obj("project" -> project)
    }.getOrElse(Json.obj())

    val jsonQuery = JsonQueryBuilder().query(byProject ++ byLevel).sort("date" -> Descending)
    JsonQueryHelpers.find(collectAsync, jsonQuery).toList(max)
  }

  def byProjectBefore(project: String, before: Date, levelOpt: Option[String] = None, max: Int = Config.mongodb.limit): Future[List[JsValue]] = {
    val byProject = Json.obj("project" -> project)
    val byBefore = Json.obj("date" -> Json.obj("$lt" -> Json.obj("$date" -> before.getTime)))
    val byLevel = levelOpt.map { level =>
      Json.obj("level" -> level)
    }.getOrElse(Json.obj())

    val jsonQuery = JsonQueryBuilder().query(byProject ++ byBefore ++ byLevel).sort("date" -> Descending)
    JsonQueryHelpers.find(collectAsync, jsonQuery).toList(max)
  }

  def byProjectAfter(project: String, before: Date, levelOpt: Option[String] = None, max: Int = Config.mongodb.limit): Future[List[JsValue]] = {
    val byProject = Json.obj("project" -> project)
    val byAfter = Json.obj("date" -> Json.obj("$gt" -> Json.obj("$date" -> before.getTime)))
    val byLevel = levelOpt.map { level =>
      Json.obj("level" -> level)
    }.getOrElse(Json.obj())

    val jsonQuery = JsonQueryBuilder().query(byProject ++ byAfter ++ byLevel).sort("date" -> Descending)
    JsonQueryHelpers.find(collectAsync, jsonQuery).toList(max)
  }

  def countByLevel(projects: String*): List[(String, Double)] = {
    val mapFunction = """
    function() {
        emit(this.level, { count: 1 });
    }
    """

    val reduceFunction = """
    function(key, values) {
        var result = { count:  0 };
        values.forEach(function(value) {
            result.count += value.count;
        });
        return result;
     }
     """

    val byProjects = projects.size match {
      case size: Int if size > 0 => Some("project" $in projects.toArray)
      case _ => None
    }

    val results = collection.mapReduce(
      mapFunction,
      reduceFunction,
      MapReduceInlineOutput,
      byProjects
    ).cursor.toList

    results.flatMap { result =>
      for {
        level <- result.getAs[String]("_id")
        value <- result.getAs[DBObject]("value")
        count <- value.getAs[Double]("count")
      } yield {
        (level, count)
      }
    }
  }

  def create(stream: Enumerator[JsValue]): Future[Int] = {
    val adaptedStream = stream.map { json =>
      val j = Log.writeForMongo.writes(json)
      println("here!")
      j
    }
    collectAsync.insert[JsValue](adaptedStream, 1)
  }

  def create(log: JsObject): Future[LastError] = {
    val message = (log \ "message").as[String]
    val keywords = asKeywords(asWords(message))
    val fullLog = log ++ keywords
    println(fullLog)
    collectAsync.insert[JsValue](fullLog)
  }

  def comment(id: ObjectId, comment: JsValue) = {
    val byId = Json.obj("_id" -> Json.obj("$oid" -> id.toString))
    val toComments = Json.obj(
      "$push" -> Json.obj("comments" -> Comment.writeForMongo.writes(comment))
    )
    collectAsync.update[JsValue, JsValue](byId, toComments)
  }

  val readFromWeb: Reads[LogFromWeb] = {
    (
      (__ \ 'project).read[String] and
      (__ \ 'logger).read[String] and
      (__ \ 'className).read[String] and
      (__ \ 'date).read[Date] and
      (__ \ 'file).read[String] and
      (__ \ 'location).read[String] and
      (__ \ 'line).read[Long] and
      (__ \ 'message).read[String] and
      (__ \ 'method).read[String] and
      (__ \ 'level).read[String] and
      (__ \ 'thread).read[String]
    ) tupled
  }

  val writeForStream: Writes[JsValue] = {
    val id = new ObjectId
    (
      (__).json.pick and
      (__ \ "_id").json.put(
        JsString(id.toString)
      ) and
      (__ \ "comments").json.put(Json.arr())
    ) join
  }

  val writeForMongo: Writes[JsValue] = {
    val id = new ObjectId
    (
      (__).json.pick and
      (__ \ "_id").json.put(
        Json.obj("$oid" -> id.toString)
      ) and
      (__ \ "date").json.put(
        (__ \ "date").json.pick.transform { json =>
          Json.obj("$date" -> json \ "date")
        }
      )
    ) join
  }

  val writeForWeb: Writes[JsValue] = {
    (
      (__).json.pick and
      (__ \ "_id").json.put(
        (__ \ "_id").json.pick.transform { json =>
          json \ "_id" \ "$oid"
        }
      ) and
      (__ \ "date").json.put(
        (__ \ "date").json.pick.transform { json =>
          json \ "date" \ "$date"
        }
      )
    ) join
  }

  implicit object LogFormat extends Format[Log] {
    def reads(json: JsValue): JsResult[Log] = JsSuccess(Log(
      (json \ "_id").asOpt[String].map(new ObjectId(_)) getOrElse new ObjectId,
      (json \ "project").as[String],
      (json \ "logger").as[String],
      (json \ "className").as[String],
      (json \ "date").as[Date],
      (json \ "file").as[String],
      (json \ "location").as[String],
      (json \ "line").as[Long],
      (json \ "message").as[String],
      (json \ "method").as[String],
      (json \ "level").as[String],
      (json \ "thread").as[String],
      (json \ "comments").asOpt[Seq[Comment]] getOrElse Nil
    ))

    def writes(l: Log): JsValue = {
      Json.obj(
        "_id"       -> l._id.toString,
        "project"   -> l.project,
        "logger"    -> l.logger,
        "className" -> l.className,
        "date"      -> l.date.getTime,
        "file"      -> l.file,
        "location"  -> l.location,
        "line"      -> l.line,
        "message"   -> l.message,
        "method"    -> l.method,
        "level"     -> l.level,
        "thread"    -> l.thread,
        "comments"  -> toJson(l.comments)
      )
    }
  }
}
