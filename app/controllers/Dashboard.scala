package controllers

import scalaz.OptionW
import scalaz.Scalaz._
import scala.concurrent.Future
import java.util.Date
import scalaz.OptionW
import scalaz.Scalaz._
import play.api._
import play.api.mvc._
import play.api.libs.iteratee._
import play.api.libs.concurrent._
import play.api.libs.Comet
import play.api.libs.EventSource
import play.api.libs.json._
import play.api.libs.json.Json._
import play.api.templates._
import play.api.libs.concurrent.execution.defaultContext
import play.api.Play.current
import reactivemongo.core.commands.LastError
import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.Imports._
import actors.StoryActor
import actors.StoryActor._
import akka.pattern.ask
import akka.util.Timeout
import akka.util.duration._
import utils.mongo.MongoUtils

import models.{ Log, User, Project, Comment, Searchable, DashboardData }

object Dashboard extends Controller with Secured with Pulling {

  def listen(project: String) = Authenticated { implicit request =>
    Logger.info("[Dashboard] Waitings logs...")
    AsyncResult {
      val ts = new Date().getTime
      val chanID = (request.user.email, project, ts)
      implicit val timeout = Timeout(5 second)
      (StoryActor.ref ? Listen(chanID)).mapTo[Enumerator[JsValue]].asPromise.map { chunks =>
        implicit val logPulling = Comet.CometMessage[JsValue] { log =>
          Json.obj(
            "log" -> toJson(log),
            "src" -> JsString(request.uri)
          ).toString
        }
        playPulling(project, chunks).getOrElse(BadRequest)
      }
    }
  }

  def search(project: String, keywords: List[String], level: Option[String]) = Authenticated { implicit request =>
    Logger.info("[Dashboard] Searching logs for project " + project)
    AsyncResult {
      Log.search(projects(project), Searchable.asRegex(keywords), level).map { foundLogs =>
        Ok(JsArray(
          foundLogs.reverse.map(wrappedLog)
        ))
      }
    }
  }

  def inbox(project: String) = Authenticated { implicit request =>
    Logger.info("[Dashboard] Getting inbox data of %s...".format(project))
    val counters = Log.countByLevel(request.user.projectNames)
    val inboxCounters = JsArray(
      counters.map { case(level, count) =>
        Json.obj(
          "counter" -> Json.obj("level" -> level, "count" -> count),
          "src" -> JsString(request.uri)
        )
      }
    )
    Ok(inboxCounters)
  }

  def comment(project: String, id: String) = Authenticated { implicit request =>
    Logger.info("[Dashboard] Comment log #%s from project %s".format(id, project))
    val logId = new ObjectId(id)
    request.body.asJson.map { rawComment =>
      Async {
        Log.byId(logId).flatMap {
          case Some(_) => {
            val comment = Comment.completeAuthor(request.user, rawComment)
            Log.comment(logId, comment).map {
              MongoUtils.handleLastError(
                _,
                Ok,
                errorMsg => InternalServerError("Failed to comment one log :" + errorMsg)
              )
            }
          }
          case _ => {
            Promise.pure(
              BadRequest("Failed to comment log. The follow log was not found: " + id)
            )
          }
        }
      }
    } getOrElse BadRequest("Malformated JSON comment: " + request.body)
  }

  def bookmark(project: String, id: String) = Authenticated { implicit request =>
    Logger.info("[Dashboard] Bookmark log #%s from project %s".format(id, project))
    val logId = new ObjectId(id)
    if(!request.user.hasBookmark(logId)) {
      Async {
        Log.byId(logId).flatMap {
          case Some(_) =>
            request.user.bookmark(logId).map {
              MongoUtils.handleLastError(
                _,
                Ok,
                errorMsg => InternalServerError("Failed to bookmark one log :" + errorMsg)
              )
            }
          case _ => Promise.pure(
            BadRequest("Failed to bookmark a log. It was not found")
          )
        }
      }
    } else BadRequest("Failed to bookmark a log. It is already bookmarked")
  }

  def bookmarks() = Authenticated { implicit request =>
    Logger.info("[Dashboard] Getting all bookmarks ")
    Async {
      request.user.bookmarks.map { bookmarkedLogs =>
        Ok(JsArray(
          bookmarkedLogs.reverse.map(wrappedLog)
        ))
      }
    }
  }

  def byLevel(project: String, level: String) = Authenticated { implicit request =>
    Logger.info("[Dashboard] Getting logs by level for %s".format(project))
    Async {
      Log.byLevel(level, projects(project)).map { logs =>
        Ok(JsArray(
          logs.reverse.map(wrappedLog)
        ))
      }
    }
  }

  def more(project: String, id: String, limit: Int, level: Option[String]) = Authenticated { implicit request =>
    Logger.info("[Dashboard] Getting more logs from project %s and log %s.".format(project, id))
    val logRefId = new ObjectId(id)
    Async {
      Log.byId(logRefId).flatMap { logRefOpt =>
        (for {
          logRef <- logRefOpt
          date   <- Log.json.date(logRef)
        } yield {
          Log.byProjectBefore(projects(project), date, level, limit).map { logsAfter =>
            Ok(JsArray(
              logsAfter.reverse.map(wrappedLog)
            ))
          }
        }) getOrElse Promise.pure(
            BadRequest("Failed to get more logs. The following log was not found: " + id)
        )
      }
    }
  }

  def withContext(project: String, id: String, limit: Int) = Authenticated { implicit request =>
    Logger.info("[Dashboard] Getting on log %s with its context for project %s.".format(project, id))
    val limitBefore = scala.math.round(limit/2)
    val logId = new ObjectId(id)
    Async {
      Log.byId(logId).flatMap { logOpt =>
        (for {
          log  <- logOpt
          date <- Log.json.date(log)
        } yield {
          Log.byProjectBefore(projects(project), date, None, limitBefore).flatMap { beforeLogs =>
            val limitAfter = (limitBefore - beforeLogs.size) + limitBefore
            Log.byProjectAfter(projects(project), date, None, limitAfter).map { afterLogs =>
              val logWithContext = afterLogs ::: (log :: beforeLogs)
              Ok(JsArray(
                logWithContext.reverse.map(wrappedLog)
              ))
            }
          }
        }) getOrElse Promise.pure(
          BadRequest("[Dashboard] Failed to getting one log with his context: The following log was not found: " + id)
        )
      }
    }
  }

  def lastFrom(project: String, from: Long) = Authenticated { implicit request =>
    Logger.info("[Dashboard] Getting history of %s from %".format(project, from))
    Async {
      project match {
        case Project.ALL => Log.all(request.user.projectNames).map { logs =>
          Ok(JsArray(
            logs.reverse.map(wrappedLog)
          ))
        }
        case _ => Log.byProjectAfter(projects(project), new Date(from)).map { logs =>
          Ok(JsArray(
            logs.reverse.map(wrappedLog)
          ))
        }
      }
    }
  }

  def last(project: String) = Authenticated { implicit request =>
    Logger.info("[Dashboard] Getting history of %s".format(project))
    Async {
      project match {
        case Project.ALL => Log.all(request.user.projectNames).map { logs =>
          Ok(JsArray(
            logs.reverse.map(wrappedLog)
          ))
        }
        case _ => Log.byProject(projects(project)).map { logs =>
          Ok(JsArray(
            logs.reverse.map(wrappedLog)
          ))
        }
      }
    }
  }

  def eval() = Action { implicit request =>
      request.body.asJson.map { json =>
        json.validate(Log.readFromWeb) match {
          case s: JsSuccess[_] => {
            StoryActor.ref ! NewLog(Log.writeForStream.writes(json));
            Ok
          }
          case JsError(errors) => {
            Logger.error("[Dashbord] - Failed to validate the json : " + json)
            BadRequest("Failed to validate json")
          }
        }
      } getOrElse BadRequest
  }

  private def wrappedLog(log: JsValue)(implicit request: RequestHeader): JsValue = {
    Json.obj(
      "log" -> Log.writeForWeb.writes(log),
      "src" -> request.uri
    )
  }

  private def projects(project: String)(implicit request: AuthenticatedRequest): List[String] = {
    project match {
      case Project.ALL => request.user.projectNames
      case _ => List(project)
    }
  }
}

// val log = Json.obj(
//   "_id" -> Json.obj("$oid" -> "5045247b1a88fd5a5d4486c2"),
//   "project" -> "onconnect",
//   "logger" -> "play",
//   "className" -> "org.apache.log4j.Category",
//   "date" -> Json.obj("$date" -> 1346708603820L),
//   "file" -> "Logger.java",
//   "location" -> "play.Logger.info(Logger.java:289)",
//   "line" -> 289.0,
//   "message" -> "Module geonaute is available (/Users/litig/Projects/hackDay/portailgeonaute/application/modules/geonaute)",
//   "method" -> "info",
//   "level" -> "INFO",
//   "thread" -> "main",
//   "comments" -> Json.arr(
//     Json.obj("_id" -> 1234567),
//     Json.obj("_id" -> 98766554)
//   )
// )
