package models

import scala.concurrent.Future
import scalaz.OptionW
import scalaz.Scalaz._
import play.Logger
import play.api.libs.concurrent._
import play.api.libs.json._
import play.api.libs.json.Json._
import play.api.mvc.{ Request, AnyContent }
import play.modules.reactivemongo.PlayBsonImplicits.{ JsValueWriter, JsValueReader }
import com.mongodb.casbah.Imports._
import com.mongodb.casbah.MongoConnection
import reactivemongo.core.commands.LastError
import reactivemongo.api.SortOrder.{ Ascending, Descending }
import utils.reactivemongo._
import utils.reactivemongo.{ QueryBuilder => JsonQueryBuilder }
import db.MongoDB

case class User(
  _id: ObjectId,
  lastname: String,
  firstname: String,
  email: String,
  language: String,
  avatar: Option[String],
  projectNames: List[String],
  bookmarkIds: List[ObjectId]
) {

  def fullName = firstname + " " + lastname

  def hasBookmark(logId: ObjectId): Boolean = bookmarkIds.find(_ == logId).isDefined

  def isFollowProject(project: String): Boolean = projectNames.find(_ == project).isDefined

  lazy val bookmarks: Future[List[JsValue]] = Log.byIds(bookmarkIds)

  def projects: Future[List[JsValue]] = Project.byNames(projectNames:_*)

  def follow(projectName: String): Option[Future[LastError]] =
    if(!projectNames.find(_ == projectName).isDefined) None
    else Some(User.follow(_id, projectName))

  def bookmark(keptLog: ObjectId) = User.bookmark(_id, keptLog)
}

object User extends MongoDB("users") {

  def apply(lastname: String,
            firstname: String,
            email: String,
            language: String,
            avatar: Option[String] = None,
            projects: List[String] = Nil,
            bookmarkIds: List[ObjectId] = Nil): User = {
    User(new ObjectId, lastname, firstname, email, language, avatar, projects, bookmarkIds)
  }

  def assignAvatar(user: User): User = {
    user.email match {
      case "srenault.contact@gmail.com" => user.copy(avatar = Some("/assets/images/avatars/srenault.contact@gmail.com.png"))
      case _ => user.copy(avatar = Some("/assets/images/avatars/sre@zenexity.com.png"))
    }
  }

  def anonymous: User = User("anonymous", "anonymous", "anonymous@unknown.com", "en")

  def byId(id: ObjectId): Future[Option[JsValue]] = {
    val byId = Json.obj("_id" -> Json.obj("$oid" -> id.toString))
    val jsonQuery = JsonQueryBuilder().query(byId)
    JsonQueryHelpers.find(collectAsync, jsonQuery).headOption
  }

  def byEmail(email: String): Future[Option[JsValue]] = {
    val byEmail = Json.obj("email" -> email)
    val jsonQuery = JsonQueryBuilder().query(byEmail)
    JsonQueryHelpers.find(collectAsync, jsonQuery).headOption
  }

  def authenticate(pseudo: String, password: String): Future[Option[JsValue]] = {
    val byPseudo = Json.obj("pseudo" -> pseudo)
    val byPassword = Json.obj("password" -> password)
    val jsonQuery = JsonQueryBuilder().query(byPseudo ++ byPassword)
    JsonQueryHelpers.find(collectAsync, jsonQuery).headOption
  }

  def create(user: JsValue) = collectAsync.insert[JsValue](user)

  def createIfNot(user: JsValue): Future[Option[LastError]] = {
    val email: String = (user \ "email").as[String]
    byEmail(email).flatMap { userOpt =>
      userOpt.map(_ => Promise.pure(None)) getOrElse create(user).map(Some(_))
    }
  }

  def follow(id: ObjectId, project: String): Future[LastError] = {
    val byId = Json.obj("_id" -> Json.obj("$oid" -> id.toString))
    val toProjects = Json.obj("$addToSet" -> Json.obj("projects" -> project))
    collectAsync.update[JsValue, JsValue](byId, toProjects)
  }

  def bookmark(id: ObjectId, keptLog: ObjectId): Future[LastError] = {
    val byId = Json.obj("_id" -> Json.obj("$oid" -> id.toString))
    val keptLogId = Json.obj("$oid" -> keptLog.toString)
    val newBookmark = Json.obj("$addToSet" -> Json.obj("bookmarkIds" -> keptLogId))
    collectAsync.update[JsValue, JsValue](byId, newBookmark)
  }

  implicit object UserFormat extends Format[User] {
    def reads(json: JsValue) = User(
      (json \ "id").asOpt[String].map(id => new ObjectId(id)).getOrElse(new ObjectId),
      (json \ "lastname").as[String],
      (json \ "firstname").as[String],
      (json \ "email").as[String],
      (json \ "language").as[String],
      (json \ "avatar").asOpt[String],
      Nil,//(json \ "projects").as[List[String]],
      Nil
    )

    def writes(user: User) = Json.obj(
      "id" -> user._id.toString,
      "lastname" -> user.lastname,
      "firstname" -> user.firstname,
      "email" -> user.email,
      "language" -> user.language,
      "avatar" -> user.avatar,
      "projects" -> user.projectNames,
      "bookmarkIds" -> JsArray(user.bookmarkIds.map(l => Json.obj("$oid" -> l.toString)))
    )
  }
}
