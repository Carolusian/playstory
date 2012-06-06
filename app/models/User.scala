package models

import com.mongodb.casbah.Imports._
import com.mongodb.casbah.MongoConnection
import play.api.mvc.{ Request, AnyContent }
import play.Logger
import db.MongoDB

case class User(
  lastname: String,
  firstname: String,
  email: String,
  language: String
) {

  def asMongoDBObject: MongoDBObject = {
    val user = MongoDBObject.newBuilder
    user += "lastname" -> lastname
    user += "firstname" -> firstname
    user += "email" -> email
    user += "language" -> language
    user.result
  }
}

object User extends MongoDB("users") {

  def fromMongoDBObject(user: MongoDBObject): Option[User] = {
    for {
      lastname  <- user.getAs[String]("lastname")
      firstname <- user.getAs[String]("firstname")
      email     <- user.getAs[String]("email")
      language  <- user.getAs[String]("language")
    } yield(User(lastname, firstname, email, language))
  }

  def byEmail(email: String): Option[User] =
    findOne("email" -> email).flatMap(User.fromMongoDBObject(_))

  def authenticate(pseudo: String, password: String): Option[User] =
    findOne("pseudo" -> pseudo, "password" -> password).flatMap(User.fromMongoDBObject(_))

  def create(user: User) = save(user.asMongoDBObject)
}
