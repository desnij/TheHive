package org.thp.thehive.controllers.v0

import scala.util.Try

import play.api.libs.json.{JsArray, Json}
import play.api.test.{FakeRequest, NoMaterializer, PlaySpecification}

import akka.stream.Materializer
import org.specs2.mock.Mockito
import org.specs2.specification.core.{Fragment, Fragments}
import org.thp.scalligraph.AppBuilder
import org.thp.scalligraph.models.{Database, DatabaseProviders, DummyUserSrv}
import org.thp.scalligraph.steps.StepsOps._
import org.thp.thehive.TestAppBuilder
import org.thp.thehive.dto.v0._
import org.thp.thehive.models.{DatabaseBuilder, Permissions}
import org.thp.thehive.services.{CaseSrv, OrganisationSrv}

class ShareCtrlTest extends PlaySpecification with Mockito {
  val dummyUserSrv               = DummyUserSrv(userId = "admin@thehive.local", permissions = Permissions.all)
  implicit val mat: Materializer = NoMaterializer

  Fragments.foreach(new DatabaseProviders().list) { dbProvider =>
    val app: AppBuilder = TestAppBuilder(dbProvider)
    step(setupDatabase(app)) ^ specs(dbProvider.name, app) ^ step(teardownDatabase(app))
  }

  def setupDatabase(app: AppBuilder): Try[Unit] =
    app.instanceOf[DatabaseBuilder].build()(app.instanceOf[Database], dummyUserSrv.getSystemAuthContext)

  def teardownDatabase(app: AppBuilder): Unit = app.instanceOf[Database].drop()

  def specs(name: String, app: AppBuilder): Fragment = {
    val shareCtrl: ShareCtrl = app.instanceOf[ShareCtrl]
    val caseSrv: CaseSrv     = app.instanceOf[CaseSrv]
    val db: Database         = app.instanceOf[Database]
    val organisationCtrl     = app.instanceOf[OrganisationCtrl]
    val orgaSrv              = app.instanceOf[OrganisationSrv]

    def getShares(caseId: String) = {
      val requestGet = FakeRequest("GET", s"/api/case/$caseId/shares")
        .withHeaders("user" -> "user2@thehive.local", "X-Organisation" -> "admin")
      val resGet = shareCtrl.listShareCases(caseId)(requestGet)

      status(resGet) must equalTo(200).updateMessage(s => s"$s\n${contentAsString(resGet)}")

      val l = contentAsJson(resGet).as[List[OutputShare]]

      l
    }

    def getSomeShares(caseId: String, user: String, orga: String) = {
      val requestGet = FakeRequest("GET", s"/api/case/$caseId/shares")
        .withHeaders("user" -> s"$user@thehive.local", "X-Organisation" -> orga)
      val resGet = shareCtrl.listShareCases(caseId)(requestGet)

      status(resGet) must equalTo(200).updateMessage(s => s"$s\n${contentAsString(resGet)}")

      val l = contentAsJson(resGet).as[List[OutputShare]]

      l
    }

    def getTaskShares(caseId: String, taskId: String, user: String, orga: String) = {
      val request = FakeRequest("GET", s"""/api/case/$caseId/task/$taskId/shares""")
        .withHeaders("user" -> s"$user@thehive.local", "X-Organisation" -> orga)
      val result = shareCtrl.listShareTasks(caseId, taskId)(request)

      status(result) shouldEqual 200

      contentAsJson(result).as[List[OutputShare]]
    }

    "manage shares for a case" in {
      val inputShare = Json.obj("shares" -> List(Json.toJson(InputShare("cert", "all", TasksFilter.all, ObservablesFilter.all))))

      val request = FakeRequest("POST", "/api/case/#4/shares")
        .withJsonBody(inputShare)
        .withHeaders("user" -> "user2@thehive.local", "X-Organisation" -> "admin")
      val result = shareCtrl.shareCase("#4")(request)

      status(result) must equalTo(200).updateMessage(s => s"$s\n${contentAsString(result)}")

      val requestAgain = FakeRequest("POST", "/api/case/#4/shares")
        .withJsonBody(inputShare)
        .withHeaders("user" -> "user2@thehive.local", "X-Organisation" -> "admin")
      val result2 = shareCtrl.shareCase("#4")(requestAgain)

      status(result2) must equalTo(400).updateMessage(s => s"$s\n${contentAsString(result2)}")

      val l     = getShares("#4")
      val share = l.find(_.organisationName == "cert")

      share must beSome.which(s => {
        s.profileName shouldEqual "all"
        s.organisationName shouldEqual "cert"
      })

      val l2     = getShares("#4")
      val share2 = l2.find(_.organisationName == "cert")

      share2 must beSome.which(s => {
        s.profileName shouldEqual "all"
        s.organisationName shouldEqual "cert"
      })
    }

    "handle share post correctly" in db.roTransaction { implicit graph =>
      // Prepare data: create an organisation, link it to cert
      val requestOrga = FakeRequest("POST", "/api/v0/organisation")
        .withJsonBody(Json.toJson(InputOrganisation(name = "orga1", "no description")))
        .withHeaders("user" -> "admin@thehive.local")
      val resultOrga = organisationCtrl.create(requestOrga)
      status(resultOrga) must beEqualTo(201)
      val requestBulkLink = FakeRequest("PUT", s"/api/organisation/cert/links")
        .withHeaders("user" -> "admin@thehive.local")
        .withJsonBody(Json.parse("""{"organisations":["orga1"]}"""))
      val resultBulkLink = organisationCtrl.bulkLink("cert")(requestBulkLink)
      status(resultBulkLink) shouldEqual 201

      val request = FakeRequest("POST", s"/api/case/#3/shares")
        .withHeaders("user" -> "user5@thehive.local", "X-Organisation" -> "cert")
        .withJsonBody(
          Json.obj(
            "shares" -> List(
              Json.toJson(InputShare("orga1", "all", TasksFilter.none, ObservablesFilter.all))
            )
          )
        )
      val result = shareCtrl.shareCase("#3")(request)

      status(result) shouldEqual 200
      getSomeShares("#3", "user5", "cert").length shouldEqual 1

      val tasks = caseSrv.get("#3").tasks(dummyUserSrv.authContext).toList
      tasks must not(beEmpty)
      val task6 = tasks.find(_.title == "case 3 task 2")
      task6 must beSome
      getTaskShares("#3", task6.get._id, "user5", "cert").filter(_.organisationName == "orga1") must beEmpty

      val requestAddTask = FakeRequest("POST", s"/api/case/task/${task6.get._id}/shares")
        .withHeaders("user" -> "user5@thehive.local", "X-Organisation" -> "cert")
        .withJsonBody(Json.obj("organisations" -> List("orga1")))
      val resultAddTask = shareCtrl.shareTask(task6.get._id)(requestAddTask)

      status(resultAddTask) shouldEqual 204
      getTaskShares("#3", task6.get._id, "user5", "cert").filter(_.organisationName == "orga1") must not(beEmpty)

      val requestEmpty = FakeRequest("POST", s"/api/case/#3/shares")
        .withHeaders("user" -> "user5@thehive.local", "X-Organisation" -> "cert")
        .withJsonBody(Json.obj("shares" -> JsArray.empty))
      val resultEmpty = shareCtrl.shareCase("#3")(requestEmpty)

      status(resultEmpty) shouldEqual 200
    }

    "remove a share" in {
      val requestBulkLink = FakeRequest("PUT", s"/api/organisation/cert/links")
        .withHeaders("user" -> "admin@thehive.local")
        .withJsonBody(Json.parse("""{"organisations":["admin"]}"""))
      val resultBulkLink = organisationCtrl.bulkLink("cert")(requestBulkLink)

      status(resultBulkLink) must equalTo(201).updateMessage(s => s"$s\n${contentAsString(resultBulkLink)}")

      val request = FakeRequest("POST", s"/api/case/#1/shares")
        .withHeaders("user" -> "user5@thehive.local", "X-Organisation" -> "cert")
        .withJsonBody(
          Json.obj(
            "shares" -> List(
              Json.toJson(InputShare("admin", "read-only", TasksFilter.all, ObservablesFilter.all))
            )
          )
        )
      val result = shareCtrl.shareCase("#1")(request)

      status(result) must equalTo(200).updateMessage(s => s"$s\n${contentAsString(result)}")
      getSomeShares("#1", "user5", "cert").length shouldEqual 1

      val share = getSomeShares("#1", "user5", "cert").find(_.organisationName == "admin").get

      val requestRemove = FakeRequest("DELETE", s"/api/case/share/${share._id}")
        .withHeaders("user" -> "user5@thehive.local", "X-Organisation" -> "cert")
      val resultRemove = shareCtrl.removeShare(share._id)(requestRemove)

      status(resultRemove) must equalTo(204).updateMessage(s => s"$s\n${contentAsString(resultRemove)}")
    }.pendingUntilFixed("not sure of 'else if (!shareSrv.get(shareId).byOrganisationName(authContext.organisation).exists())' in ShareCtrl")

    "patch a share" in {
      val requestBulkLink = FakeRequest("PUT", s"/api/organisation/cert/links")
        .withHeaders("user" -> "admin@thehive.local")
        .withJsonBody(Json.parse("""{"organisations":["admin"]}"""))
      val resultBulkLink = organisationCtrl.bulkLink("cert")(requestBulkLink)

      status(resultBulkLink) must equalTo(201).updateMessage(s => s"$s\n${contentAsString(resultBulkLink)}")

      val request = FakeRequest("POST", s"/api/case/#2/shares")
        .withHeaders("user" -> "user5@thehive.local", "X-Organisation" -> "cert")
        .withJsonBody(
          Json.obj(
            "shares" -> List(
              Json.toJson(InputShare("admin", "read-only", TasksFilter.all, ObservablesFilter.all))
            )
          )
        )
      val result = shareCtrl.shareCase("#2")(request)

      status(result) must equalTo(200).updateMessage(s => s"$s\n${contentAsString(result)}")

      val l = getSomeShares("#2", "user5", "cert")

      l.length shouldEqual 1

      val share = l.find(s => s.organisationName == "admin" && s.profileName == "read-only")

      share must beSome
      l.find(s => s.organisationName == "admin" && s.profileName == "all") must beNone

      val requestPatch = FakeRequest("PATCH", s"/api/case/share/${share.get._id}")
        .withHeaders("user" -> "user5@thehive.local", "X-Organisation" -> "cert")
        .withJsonBody(Json.parse("""{"profile": "all"}"""))
      val resultPatch = shareCtrl.updateShare(share.get._id)(requestPatch)

      status(resultPatch) shouldEqual 200

      val newL = getSomeShares("#2", "user5", "cert")

      newL.length shouldEqual 1
      newL.find(s => s.organisationName == "admin" && s.profileName == "all") must beSome
    }

    "fetch and remove observable shares" in db.roTransaction { implicit graph =>
//      val observables = caseSrv
//        .get("#1")
//        .observables(DummyUserSrv(userId = "user1@thehive.local", organisation = "cert", permissions = Permissions.all).authContext)
//        .toList
//
//      observables must not(beEmpty)
//
//      val observableHfr = observables.find(_.message.contains("Some weird domain"))
//
//      observableHfr must beSome
//
//      def getObsShares = {
//        val request = FakeRequest("GET", s"/api/case/#1/observable/${observableHfr.get._id}/shares")
//          .withHeaders("user" -> "user2@thehive.local", "X-Organisation" -> "admin")
//        val result = shareCtrl.listShareObservables("#1", observableHfr.get._id)(request)
//
//        status(result) shouldEqual 200
//
//        contentAsJson(result).as[List[OutputShare]]
//      }
//
//      val l = getObsShares
//
//      l.length shouldEqual 1
//
//      val requestAdd = FakeRequest("POST", s"/api/case/observable/${observableHfr.get._id}/shares")
//        .withHeaders("user" -> "user2@thehive.local", "X-Organisation" -> "admin")
//        .withJsonBody(Json.obj("organisations" -> List("cert")))
//      val resultAdd = shareCtrl.shareObservable(observableHfr.get._id)(requestAdd)
//
//      status(resultAdd) shouldEqual 204
//      getObsShares.length shouldEqual 1
//
//      val requestDel = FakeRequest("DELETE", s"/api/observable/shares")
//        .withHeaders("user" -> "user2@thehive.local", "X-Organisation" -> "admin")
//        .withJsonBody(Json.obj("ids" -> List(l.head._id)))
//      val resultDel = shareCtrl.removeObservableShares()(requestDel)
//
//      status(resultDel) shouldEqual 204
//      getObsShares must beEmpty
      pending("shareCtrl.removeObservableShares has been refactor, need to rewrite test")
    }

    "fetch, add and remove shares for a task" in db.roTransaction { implicit graph =>
      // Create a case with a task first
//      val c = db
//        .tryTransaction(
//          implicit graph =>
//            caseSrv.create(
//              Case(0, "case audit", "desc audit", 1, new Date(), None, flag = false, 1, 1, CaseStatus.Open, None),
//              None,
//              orgaSrv.getOrFail("admin").get,
//              Set.empty,
//              Map.empty,
//              None,
//              Seq(Task("task 1 new case", "group 666", None, TaskStatus.Waiting, flag = false, None, None, 0, None) -> None)
//            )(graph, dummyUserSrv.authContext)
//        )
//        .get
//      val task4 = caseSrv.get(c._id).tasks(dummyUserSrv.getSystemAuthContext).toList.find(_.title == "task 1 new case")
//
//      def getTaskShares = {
//        val request = FakeRequest("GET", s"/api/case/${c._id}/task/${task4.get._id}/shares")
//          .withHeaders("user" -> "user5@thehive.local", "X-Organisation" -> "cert")
//        val result = shareCtrl.listShareTasks(c._id, task4.get._id)(request)
//
//        status(result) must equalTo(200).updateMessage(s => s"$s\n${contentAsString(result)}")
//
//        contentAsJson(result).as[List[OutputShare]]
//      }
//
//      val l = getTaskShares
//
//      l must not(beEmpty)
//
//      val requestAdd = FakeRequest("POST", s"/api/case/task/${task4.get._id}/shares")
//        .withHeaders("user" -> "user1@thehive.local", "X-Organisation" -> "cert")
//        .withJsonBody(Json.obj("organisations" -> List("admin")))
//      val resultAdd = shareCtrl.shareTask(task4.get._id)(requestAdd)
//
//      status(resultAdd) shouldEqual 204
//      getTaskShares.length shouldEqual l.length
//
//      val requestDel = FakeRequest("DELETE", s"/api/task/shares")
//        .withHeaders("user" -> "user5@thehive.local", "X-Organisation" -> "cert")
//        .withJsonBody(Json.obj("ids" -> List(l.head._id)))
//      val resultDel = shareCtrl.removeTaskShares()(requestDel)
//
//      status(resultDel) shouldEqual 204
//      getTaskShares must beEmpty
      pending("shareCtrl.removeTaskShares has been refactor, need to rewrite test")
    }
  }

}
