/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package controllers

import javax.inject.Inject
import models.{Metadata, State, Task}
import models.Task.CompletableByType
import modules.{Auth, DAO, DB, NotifyProvider}
import org.eclipse.jgit.lib.ObjectId
import org.webjars.WebJarAssetLocator
import org.webjars.play.WebJarsUtil
import play.api.libs.json.{JsObject, Json}
import play.api.mvc._
import play.api.{Configuration, Environment, Logger, Mode}
import play.twirl.api.Html
import services.{DataFacade, GitMetadata, RuntimeReporter}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.xml.{Comment, Node}


class Application @Inject()
  (env: Environment, dataFacade: DataFacade, userAction: UserAction, auth: Auth, gitMetadata: GitMetadata, configuration: Configuration, webJarsUtil: WebJarsUtil, notifyProvider: NotifyProvider, runtimeReporter: RuntimeReporter, dao: DAO)
  (requestsView: views.html.Requests, newRequestView: views.html.NewRequest, newRequestFormView: views.html.NewRequestForm, requestView: views.html.Request, commentsView: views.html.partials.Comments, formTestView: views.html.FormTest, notifyTestView: views.html.NotifyTest, loginView: views.html.Login, pickEmailView: views.html.PickEmail, errorView: views.html.Error, openUserTasksView: views.html.OpenUserTasks, taskView: views.html.Task, searchView: views.html.Search, migratorView: views.html.Migrator, migrationResolverView: views.html.MigrationResolver)
  (implicit ec: ExecutionContext)
  extends InjectedController {

  private def withUserInfo[A](f: UserInfo => Future[Result])(implicit userRequest: UserRequest[A]): Future[Result] = {
    userRequest.maybeUserInfo.fold(auth.authUrl.map(Redirect(_)))(f)
  }

  def index = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      dataFacade.tasksForUser(userInfo.email, State.InProgress).map { tasks =>
        if (tasks.isEmpty) {
          Redirect(routes.Application.requests(None))
        }
        else {
          Redirect(routes.Application.openUserTasks())
        }
      }
    }
  }

  def openUserTasks = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      gitMetadata.latestVersion.map(_._2).flatMap { implicit metadata =>
        dataFacade.tasksForUser(userInfo.email, State.InProgress).flatMap { tasks =>

          val tasksWithProgramFuture = Future.sequence {
            tasks.map { case (task, numComments, request) =>
              for {
                program <- gitMetadata.fetchProgram(request.metadataVersion, request.program)
                prototype <- program.task(task.taskKey)
              } yield (task, prototype, numComments, request, program)
            }
          }

          tasksWithProgramFuture.map { tasksWithProgram =>
            // todo: this could be better
            if (tasks.size == tasksWithProgram.size) {
              Ok(openUserTasksView(tasksWithProgram, userInfo))
            }
            else {
              InternalServerError("Could not find a specified program")
            }
          }
        }
      }
    }
  }

  def requests(program: Option[String]) = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      gitMetadata.latestVersion.map(_._2).flatMap { implicit metadata =>

        val (maybeTitle, requestsFuture) = program.map { programKey =>
          metadata.programs.get(programKey).map(_.name) -> dataFacade.programRequests(programKey)
        } getOrElse {
          Some("Your Requests") -> dataFacade.userRequests(userInfo.email)
        }

        maybeTitle.map { title =>
          requestsFuture.map { requests =>
            Ok(requestsView(title, requests, userInfo))
          }
        } getOrElse {
          Future.successful {
            InternalServerError(errorView("Could not find program", userInfo))
          }
        }
      }
    }
  }

  def search(program: Option[String], state: Option[models.State.State], data: Option[String]) = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      gitMetadata.latestVersion.map(_._2).flatMap { implicit metadata =>
        val maybeData = data.flatMap(Json.parse(_).asOpt[JsObject])
        dataFacade.search(program, state, maybeData, None).map { requests =>
          Ok(searchView(requests, userInfo))
        }
      }
    }
  }

  def report(programKey: String, reportKey: String) = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      gitMetadata.latestVersion.map(_._2).flatMap { implicit metadata =>
        metadata.programs.get(programKey).fold(Future.successful(NotFound(errorView("Program Not Found", userInfo)))) { program =>
          program.reports.get(reportKey).fold(Future.successful(NotFound(errorView("Report Not Found", userInfo)))) { report =>
            dataFacade.search(Some(programKey), report.query.state, report.query.data, report.query.dataIn).map { requests =>
              Ok(searchView(requests, userInfo))
            }
          }
        }
      }
    }
  }

  def newRequest(maybeName: Option[String], maybeProgramKey: Option[String], maybeStartTask: Option[String]) = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      gitMetadata.latestVersion.map(_._2).flatMap { implicit metadata =>
        val nonEmptyMaybeName = maybeName.filter(_.nonEmpty)
        val nonEmptyMaybeProgramKey = maybeProgramKey.filter(_.nonEmpty)
        val nonEmptyMaybeStartTask = maybeStartTask.filter(_.nonEmpty)

        val maybeTaskView = for {
          name <- nonEmptyMaybeName
          programKey <- nonEmptyMaybeProgramKey
          programMetadata <- metadata.programs.get(programKey)
          startTask <- nonEmptyMaybeStartTask
          task <- programMetadata.tasks.get(startTask)
        } yield {
          dataFacade.requestsSimilarToName(programKey, name).map { similarRequests =>
            Ok(newRequestFormView(programKey, name, startTask, task, userInfo, similarRequests))
          }
        }

        maybeTaskView.getOrElse(Future.successful(Ok(newRequestView(userInfo, nonEmptyMaybeName, nonEmptyMaybeProgramKey, nonEmptyMaybeStartTask))))
      }
    }
  }

  def createRequest(name: String, programKey: String, startTask: String) = userAction.async(parse.json) { implicit userRequest =>
    withUserInfo { userInfo =>
      gitMetadata.latestVersion.flatMap { case (metadataVersion, metadata) =>
        metadata.program(programKey).flatMap { programMetadata =>
          programMetadata.tasks.get(startTask).fold(Future.successful(InternalServerError(s"Could not find task named '$startTask'"))) { metaTask =>
            dataFacade.createRequest(metadataVersion, programKey, name, userInfo.email).flatMap { request =>
              Task.completableByWithDefaults(metaTask.completableBy, Some(userInfo.email), None).flatMap(programMetadata.completableBy).fold {
                Future.successful(BadRequest("Could not determine who can complete the task"))
              } { emails =>
                dataFacade.createTask(request.slug, startTask, emails.toSeq, Some(userInfo.email), Json.toJson(userRequest.body).asOpt[JsObject], State.Completed).map { task =>
                  Ok(Json.toJson(request))
                }
              }
            }
          }
        }
      }
    }
  }

  def request(requestSlug: String) = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      dataFacade.request(userInfo.email, requestSlug).flatMap { request =>
        gitMetadata.fetchProgram(request.metadataVersion, request.program).flatMap { program =>
          gitMetadata.latestVersion.flatMap { case (latestVersion, latestMetadata) =>
            dataFacade.requestTasks(userInfo.email, request.slug).map { tasks =>
              Ok(requestView(program, latestVersion, request, tasks, userInfo)(userRequest, latestMetadata))
            }
          }
        }
      } recoverWith {
        case rnf: DB.RequestNotFound =>
          gitMetadata.latestVersion.map(_._2).map { implicit metadata =>
            NotFound(errorView(rnf.getMessage, userInfo))
          }
      }
    }
  }

  def updateRequest(requestSlug: String, state: State.State) = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      // todo: allow user to provide a message
      dataFacade.updateRequest(userInfo.email, requestSlug, state, None).map { request =>
        Redirect(routes.Application.request(request.slug))
      }
    }
  }

  def deleteRequest(requestSlug: String) = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      dataFacade.deleteRequest(userInfo.email, requestSlug).map { request =>
        NoContent
      }
    }
  }

  def metadataMigrate(requestSlug: String) = userAction.async(parse.formUrlEncoded) { implicit userRequest =>
    withUserInfo { userInfo =>
      val maybeVersion = userRequest.body.get("version").flatMap(_.headOption).flatMap { version =>
        Try(ObjectId.fromString(version)).toOption
      }

      dataFacade.requestMetadataMigrationConflicts(requestSlug, maybeVersion).flatMap { migrationConflicts =>
        if (migrationConflicts.isEmpty) {
          dataFacade.updateRequestMetadata(userInfo.email, requestSlug, maybeVersion, Set.empty).map { _ =>
            Redirect(routes.Application.request(requestSlug))
          }
        }
        else {
          val resolutions = migrationConflicts.map { migrationConflict =>
            userRequest.body.get(s"task-${migrationConflict.task.id}").flatMap(_.headOption).flatMap {
              case "do-nothing" =>
                Some(Metadata.MigrationConflictResolution(Metadata.MigrationConflictResolution.DoNothing, migrationConflict.task.id))

              case "reopen" =>
                Some(Metadata.MigrationConflictResolution(Metadata.MigrationConflictResolution.Reopen, migrationConflict.task.id))

              case "remove" =>
                Some(Metadata.MigrationConflictResolution(Metadata.MigrationConflictResolution.Remove, migrationConflict.task.id))

              case "new-task-key" =>
                userRequest.body.get(s"task-${migrationConflict.task.id}-newtaskkey").flatMap(_.headOption).map { newTaskKey =>
                  Metadata.MigrationConflictResolution(Metadata.MigrationConflictResolution.NewTaskKey(newTaskKey), migrationConflict.task.id)
                }

              case "reassign" =>
                val maybeCompletableBy = userRequest.body.get(s"task-${migrationConflict.task.id}-completableby").flatMap(_.headOption)
                Some(Metadata.MigrationConflictResolution(Metadata.MigrationConflictResolution.Reassign(maybeCompletableBy), migrationConflict.task.id))
            }
          }

          if (resolutions.exists(_.isEmpty)) {
            // todo: if missing resolutions, tell the user
            gitMetadata.latestVersion.map(_._2).flatMap { implicit metadata =>
              dataFacade.request(userInfo.email, requestSlug).flatMap { request =>
                gitMetadata.fetchProgram(request.metadataVersion, request.program).flatMap { currentProgram =>
                  gitMetadata.fetchProgram(maybeVersion, request.program).map { newProgram =>
                    Ok(migrationResolverView(userInfo, request, maybeVersion, currentProgram, newProgram, migrationConflicts))
                  }
                }
              }
            }
          }
          else {
            dataFacade.updateRequestMetadata(userInfo.email, requestSlug, maybeVersion, resolutions.flatten).map { _ =>
              Redirect(routes.Application.request(requestSlug))
            }
          }
        }
      }
    }
  }

  def migrator() = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      for {
        allVersions <- gitMetadata.allVersions
        (_, metadata) <- gitMetadata.latestVersion
        programs = metadata.programs.filter(_._2.isAdmin(userInfo.email)).keySet
        requests <- Future.reduceLeft(programs.map(programKey => dao.searchRequests(Some(programKey), Some(State.InProgress), None, None)))(_ ++ _)
      } yield {
        Ok(migratorView(userInfo, allVersions.toSeq.sortBy(_.date.toEpochSecond).reverse, requests)(userRequest, metadata))
      }
    }
  }

  def task(requestSlug: String, taskId: Int) = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      gitMetadata.latestVersion.map(_._2).flatMap { implicit metadata =>
        val f = for {
          request <- dataFacade.request(userInfo.email, requestSlug)
          task <- dataFacade.taskById(taskId)
          comments <- dataFacade.commentsOnTask(taskId)
          program <- gitMetadata.fetchProgram(request.metadataVersion, request.program)
          prototype <- program.task(task.taskKey)
        } yield Ok(taskView(request, program, task, prototype, comments, userInfo, program.isAdmin(userInfo.email), program.groups.keySet))

        f.recover {
          case e: Exception =>
            InternalServerError(errorView(e.getMessage, userInfo))
        }
      }
    }
  }

  def addTask(requestSlug: String) = userAction.async(parse.formUrlEncoded) { implicit userRequest =>
    withUserInfo { userInfo =>
      gitMetadata.latestVersion.map(_._2).flatMap { implicit metadata =>
        val maybeTaskPrototypeKey = userRequest.body.get("taskPrototypeKey").flatMap(_.headOption)
        val maybeCompletableBy = userRequest.body.get("completableBy").flatMap(_.headOption).filterNot(_.isEmpty)

        maybeTaskPrototypeKey.fold(Future.successful(BadRequest("No taskPrototypeKey specified"))) { taskPrototypeKey =>
          dataFacade.request(userInfo.email, requestSlug).flatMap { request =>
            gitMetadata.fetchProgram(request.metadataVersion, request.program).flatMap { program =>
              val maybeTask = for {
                task <- program.tasks.get(taskPrototypeKey)
                completableBy <- Task.completableByWithDefaults(task.completableBy, Some(request.creatorEmail), maybeCompletableBy).flatMap(program.completableBy)
              } yield (task, completableBy)

              maybeTask.fold(Future.successful(InternalServerError(s"Could not find task prototype $taskPrototypeKey"))) { case (_, completableBy) =>
                dataFacade.createTask(requestSlug, taskPrototypeKey, completableBy.toSeq).map { _ =>
                  Redirect(routes.Application.request(request.slug))
                } recover {
                  case e @ (_: DataFacade.DuplicateTaskException | _: DataFacade.MissingTaskDependencyException) =>
                    BadRequest(errorView(e.getMessage, userInfo))
                }
              }
            }
          }
        }
      }
    }
  }

  private lazy val maybeJsObject: BodyParser[Option[JsObject]] = {
    parse.tolerantText.map { s =>
      Try(Json.parse(s).as[JsObject]).toOption
    }
  }

  def updateTaskState(requestSlug: String, taskId: Int, state: State.State, completionMessage: Option[String]) = userAction.async(maybeJsObject) { implicit userRequest =>
    withUserInfo { userInfo =>
      userRequest.body.fold(dataFacade.taskById(taskId).map(_.data))(data => Future.successful(Some(data))).flatMap { maybeData =>
        dataFacade.updateTaskState(userInfo.email, taskId, state, Some(userInfo.email), maybeData, completionMessage).map { task =>
          render {
            case Accepts.Html() => Redirect(routes.Application.request(requestSlug))
            case Accepts.Json() => Ok(Json.toJson(task))
          }
        }
      }
    }
  }

  def updateTaskAssignment(requestSlug: String, taskId: Int) = userAction.async(parse.json) { implicit userRequest =>
    withUserInfo { userInfo =>
      dataFacade.request(userInfo.email, requestSlug).flatMap { request =>
        gitMetadata.latestVersion.map(_._2).flatMap { implicit metadata =>
          gitMetadata.fetchProgram(request.metadataVersion, request.program).flatMap { program =>
            val maybeCompletableBy = (userRequest.body \ "email").asOpt[String].map { email =>
              CompletableByType.Email -> email
            } orElse {
              (userRequest.body \ "group").asOpt[String].map { group =>
                CompletableByType.Group -> group
              }
            } flatMap { case (completableByType, completableByValue) =>
              program.completableBy(completableByType, completableByValue)
            }

            maybeCompletableBy.fold(Future.successful(BadRequest("Must specify an email or group"))) { emails =>
              dataFacade.assignTask(userInfo.email, taskId, emails.toSeq).map { _ =>
                Ok
              }
            }
          }
        }
      }
    }
  }

  def deleteTask(requestSlug: String, taskId: Int) = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      dataFacade.deleteTask(userInfo.email, taskId).map { _ =>
        render {
          case Accepts.Html() => Redirect(routes.Application.request(requestSlug))
          case Accepts.Json() => NoContent
        }
      }
    }
  }

  def commentOnTask(requestSlug: String, taskId: Int) = userAction.async(parse.formUrlEncoded) { implicit userRequest =>
    withUserInfo { userInfo =>
      val maybeContents = userRequest.body.get("contents").flatMap(_.headOption).filterNot(_.isEmpty)
      val maybeRedirect = userRequest.body.get("redirect").flatMap(_.headOption)

      maybeContents.fold(Future.successful(BadRequest("The contents were empty"))) { contents =>
        dataFacade.commentOnTask(requestSlug, taskId, userInfo.email, contents).map { comment =>
          Redirect {
            maybeRedirect.getOrElse(routes.Application.request(requestSlug).url)
          }
        }
      }
    }
  }

  def commentsOnTask(requestSlug: String, taskId: Int) = userAction.async(maybeJsObject) { implicit userRequest =>
    withUserInfo { userInfo =>
      dataFacade.commentsOnTask(taskId).map { comments =>
        Ok(commentsView(comments))
      }
    }
  }

  def emailReply = Action.async(parse.form(notifyProvider.form)) { implicit request =>

    def sendUnknownResponse = {
      val emailBody = "Sorry, but we couldn't figure out what to do with your email:\n\n" + request.body.body
      notifyProvider.sendMessage(Set(request.body.sender), "OSS Request Email Not Handled", emailBody)
    }

    request.body.inReplyTo.fold(sendUnknownResponse.map(_ => NotAcceptable)) { inReplyTo =>
      notifyProvider.getRootMessageDataFromId(inReplyTo).flatMap { rootMessageData =>

        val maybeData = for {
          taskIdString <- (rootMessageData \ "task-id").asOpt[String]
          taskId <- Try(taskIdString.toInt).toOption
          requestSlug <- (rootMessageData \ "request-slug").asOpt[String]
        } yield taskId -> requestSlug

        maybeData.fold(sendUnknownResponse.map(_ => NotAcceptable)) { case (taskId, requestSlug) =>
          dataFacade.commentOnTask(requestSlug, taskId, request.body.sender, request.body.body).map { _ =>
            Ok
          }
        }
      }
    }
  }

  def formTest = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      gitMetadata.latestVersion.map(_._2).map { implicit metadata =>
        Ok(formTestView(userInfo))
      }
    }
  }

  def notifyTest = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      gitMetadata.latestVersion.map(_._2).map { implicit metadata =>
        Ok(notifyTestView(userInfo))
      }
    }
  }

  def notifyTestSend = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      gitMetadata.latestVersion.map(_._2).flatMap { implicit metadata =>
        val maybeInfo = for {
          form <- userRequest.body.asFormUrlEncoded

          recipients <- form.get("recipient")
          recipient <- recipients.headOption
          if !recipient.isEmpty

          messages <- form.get("message")
          message <- messages.headOption
          if !message.isEmpty
        } yield recipient -> message

        maybeInfo.fold {
          Future.successful(BadRequest(notifyTestView(userInfo, Some(Failure(new Exception("Missing form value"))))))
        } { case (recipient, message) =>
          notifyProvider.sendMessage(Set(recipient), "Notify Test", message).map { result =>
            val message = result match {
              case s: String => s
              case _ => "Test Successful"
            }
            Ok(notifyTestView(userInfo, Some(Success(message))))
          } recover {
            case t: Throwable => Ok(notifyTestView(userInfo, Some(Failure(t))))
          }
        }
      }
    }
  }

  private def login(state: Option[String])(emails: Set[String])(implicit request: RequestHeader): Future[Result] = {
    if (emails.size > 1) {
      gitMetadata.latestVersion.map(_._2).map { implicit  metadata =>
        Ok(pickEmailView(emails, state)).withSession("emails" -> emails.mkString(","))
      }
    }
    else if (emails.size == 1) {
      val email = emails.head

      gitMetadata.latestVersion.map(_._2).map { metadata =>
        val url = state.getOrElse(controllers.routes.Application.openUserTasks().url)

        // todo: putting this info in the session means we can't easily invalidate it later
        Redirect(url).withSession("email" -> email)
      }
    }
    else {
      Future.successful(BadRequest("Could not determine user email"))
    }
  }

  def callback(code: Option[String], state: Option[String]) = Action.async { implicit request =>
    auth.emails(code).flatMap(login(state)).recover {
      case e: Exception => Unauthorized(e.getMessage)
    }
  }

  def acs() = Action.async(parse.formUrlEncoded) { implicit request =>
    val maybeState = request.body.get("RelayState").flatMap(_.headOption)
    auth.emails(request.body.get("SAMLResponse").flatMap(_.headOption)).flatMap(login(maybeState)).recover {
      case e: Exception => Unauthorized(e.getMessage)
    }
  }

  def selectEmail(email: String, state: Option[String]) = Action { request =>
    val maybeValidEmail = request.session.get("emails").map(_.split(",")).getOrElse(Array.empty[String]).find(_ == email)
    maybeValidEmail.fold(Unauthorized("Email invalid")) { validEmail =>
      val url = state.getOrElse(controllers.routes.Application.openUserTasks().url)
      // todo: putting this info in the session means we can't easily invalidate it later
      Redirect(url).withSession("email" -> validEmail)
    }
  }

  def logout() = Action.async { implicit request =>
    auth.authUrl.flatMap { authUrl =>
      gitMetadata.latestVersion.map(_._2).map { implicit metadata =>
        Ok(loginView()).withNewSession
      }
    }
  }

  private[controllers] def demoRepoAllowed(request: RequestHeader): Boolean = {
    configuration.getOptional[String]("services.repo_creator").fold(true) { psk =>
      request.headers.get(AUTHORIZATION).fold(false)(_ == s"psk $psk")
    }
  }

  def createDemoRepo() = Action(parse.json) { request =>
    val allowed = demoRepoAllowed(request)

    env.mode match {
      case Mode.Prod =>
        NotFound
      case _ if !allowed =>
        Unauthorized
      case _ =>
        Logger.info(request.body.toString)

        val json = Json.obj(
          "state" -> State.InProgress,
          "url" -> "http://asdf.com"
        )

        Created(json)
    }
  }

  // one minute after the task is created the status is switched to Completed
  def demoRepo(url: String) = Action { implicit request =>
    val allowed = demoRepoAllowed(request)

    env.mode match {
      case Mode.Prod =>
        NotFound
      case _ if !allowed =>
        Unauthorized
      case _ =>
        val json = Json.obj(
          "state" -> State.Completed,
          "url" -> "http://asdf.com",
          "data" -> Json.obj(
            "message" -> "Repo created!"
          )
        )

        Ok(json)
    }
  }

  def deleteDemoRepo(url: String) = Action { implicit request =>
    val allowed = demoRepoAllowed(request)

    env.mode match {
      case Mode.Prod =>
        NotFound
      case _ if !allowed =>
        Unauthorized
      case _ =>
        NoContent
    }
  }

  private[controllers] def svgSymbol(path: String, symbol: String): Node = {
    webJarsUtil.locate(path).path.flatMap { filePath =>
      val maybeInputStream = env.resourceAsStream(WebJarAssetLocator.WEBJARS_PATH_PREFIX + "/" + filePath)
      maybeInputStream.fold[Try[Node]](Failure(new Exception("Could not read file"))) { inputStream =>
        val elem = scala.xml.XML.load(inputStream)
        inputStream.close()

        val maybeSymbol = elem.child.find { node =>
          node \@ "id" == symbol
        } flatMap (_.child.headOption)

        maybeSymbol.fold[Try[Node]](Failure(new Exception(s"Could not find symbol $symbol")))(Success(_))
      }
    } fold (
      { t => Comment(s"Error getting SVG: ${t.getMessage}") },
      { identity }
    )
  }

  private def svgInline(path: String, symbol: String): Html = {
    Html(svgSymbol(path, symbol).toString())
  }

}

case class UserInfo(email: String)

class UserRequest[A](val maybeUserInfo: Option[UserInfo], request: Request[A]) extends WrappedRequest[A](request)

class UserAction @Inject()(val parser: BodyParsers.Default)(implicit val executionContext: ExecutionContext) extends ActionBuilder[UserRequest, AnyContent] with ActionTransformer[Request, UserRequest] {
  def transform[A](request: Request[A]): Future[UserRequest[A]] = {

    val maybeUser = request.session.get("email").map { email =>
      UserInfo(email)
    }

    Future.successful(new UserRequest[A](maybeUser, request))
  }
}
