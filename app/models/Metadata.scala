/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package models

import java.time.ZonedDateTime

import models.Task.CompletableByType
import org.eclipse.jgit.lib.ObjectId
import play.api.libs.functional.syntax._
import play.api.libs.json._
import core.Extensions._

import scala.concurrent.Future

case class MetadataVersion(id: Option[ObjectId], date: ZonedDateTime)

case class Metadata(programs: Map[String, Program]) {

  def program(programKey: String): Future[Program] = {
    programs.get(programKey).fold(Future.failed[Program](new Exception(s"Program '$programKey' not found")))(Future.successful)
  }

}

object Metadata {
  val multiprogramReads = Reads.mapReads[Program](Program.jsonReads).map(Metadata(_))
  val singleprogramReads = Program.jsonReads.map { program =>
    Metadata(Map("default" -> program))
  }

  implicit val jsonReads: Reads[Metadata] = multiprogramReads.orElse(singleprogramReads)
}

case class Program(name: String, description: Option[String], startTasks: Set[String], groups: Map[String, Set[String]], services: Map[String, String], tasks: Map[String, Task.Prototype], reports: Map[String, Report]) {
  val admins: Set[String] = groups.getOrElse("admin", Set.empty[String])

  def isAdmin(email: String): Boolean = admins.contains(email)

  def completableBy(completableBy: (CompletableByType.CompletableByType, String)): Option[Set[String]] = {
    val (completableByType, completableByValue) = completableBy
    completableByType match {
      case models.Task.CompletableByType.Group =>
        groups.get(completableByValue)
      case models.Task.CompletableByType.Email =>
        Some(Set(completableByValue))
      case models.Task.CompletableByType.Service =>
        services.get(completableByValue).map(Set(_))
    }
  }

  def descriptionMarkdown: Option[String] = description.map(_.markdown)

  def task(taskKey: String): Future[Task.Prototype] = {
    tasks.get(taskKey).fold(Future.failed[Task.Prototype](new Exception(s"Can't find task prototype with key: $taskKey")))(Future.successful)
  }
}

object Program {
  implicit val jsonReads: Reads[Program] = (
    (__ \ "name").read[String].orElse(Reads.pure("Default")) ~
    (__ \ "description").readNullable[String] ~
    (__ \ "start_tasks").read[Set[String]].orElse(Reads.pure(Set.empty[String])) ~
    (__ \ "groups").read[Map[String, Set[String]]] ~
    (__ \ "services").read[Map[String, String]].orElse(Reads.pure(Map.empty[String, String])) ~
    (__ \ "tasks").read[Map[String, Task.Prototype]] ~
    (__ \ "reports").read[Map[String, Report]].orElse(Reads.pure(Map.empty[String, Report]))
  )(Program.apply _)
}

case class DataIn(attribute: String, values: Set[String])

object DataIn {
  implicit val jsonReads: Reads[DataIn] = Json.reads[DataIn]
}

case class ReportQuery(state: Option[State.State], data: Option[JsObject], dataIn: Option[DataIn])

object ReportQuery {
  implicit val jsonReads: Reads[ReportQuery] = (
    (__ \ "state").readNullable[State.State] ~
    (__ \ "data").readNullable[JsObject] ~
    (__ \ "data-in").readNullable[DataIn]
  )(ReportQuery.apply _)
}


case class Report(title: String, query: ReportQuery)

object Report {
  implicit val jsonReads: Reads[Report] = Json.reads[Report]
}