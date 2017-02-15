package ru.devzen.shownotegen

import java.util.regex.Pattern

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.Directives.get
import akka.http.scaladsl.server.Directives.path
import akka.stream.ActorMaterializer
import org.apache.http.client.fluent.Request
import org.apache.http.entity.ContentType
import org.joda.time.DateTime
import org.joda.time.Duration
import org.joda.time.Period
import org.joda.time.format.PeriodFormatterBuilder
import org.json4s._
import org.json4s.jackson.JsonMethods._

import scala.util.Properties

case class Theme(title: String, urls: List[String], relativeStartReadableTime: String, relativeStartMs: Long)

object ShownotesGen {

  def main(args: Array[String]): Unit = {
    try {
      implicit val system = ActorSystem("shownotegenerator")
      implicit val materializer = ActorMaterializer()
      val routes = getRoute ~ trelloHook
      Http().bindAndHandle(routes, "0.0.0.0", Properties.envOrElse("PORT", "9025").toInt)
      println(s"Server online")
    } catch {
      case e: Exception => e.printStackTrace()
    }
  }

  private def getRoute = {
    (path("generate" ~ Slash.?) & get) {
      parameters('start.as[Long] ?) { manualStartTimeOpt =>
        complete {
          val discussedCardsJs = Request.Get(UrlGenerator.getDiscussedListUrl).execute().returnContent().asString()
          val cards = parse(discussedCardsJs) \ "cards"

          val processedThemes = cards.children.map { card =>
            val cardId = (card \ "id").values.asInstanceOf[String]
            val name = (card \ "name").values.asInstanceOf[String]
            val desc = (card \ "desc").values.asInstanceOf[String]

            val urls = extractUrls(desc)

            val cardActionsJs = Request.Get(UrlGenerator.getCardActionsUrl(cardId)).execute().returnContent().asString()

            val dragAndDropEvents = parse(cardActionsJs).children.filter { event =>
              val eventType = (event \ "type").values.asInstanceOf[String]
              "updateCard" == eventType
            }

            val topicStartedAtMs = dragAndDropEvents.flatMap { event =>
              val listAfterId = (event \ "data" \ "listAfter" \ "id").values.asInstanceOf[String]
              val listBeforeId = (event \ "data" \ "listBefore" \ "id").values.asInstanceOf[String]

              if (Constants.TrelloToDiscussCurrentEpisodeListId == listBeforeId && Constants.TrelloInDiscussionListId == listAfterId) {
                val dateString = (event \ "date").values.asInstanceOf[String]
                Some(DateTime.parse(dateString).getMillis)
              } else None
            }

            if (topicStartedAtMs.size != 1) {
              println("WARN - Unexpected movements of a card from 'to discuss' to 'in discussion'")
            }

            val startedRecordingAtMs = manualStartTimeOpt match {
              case Some(manualStartTime) => manualStartTime
              case None =>
                val cardInfoJs = Request.Get(UrlGenerator.getCardInfoUrl(cardId)).execute().returnContent().asString()
                val parsedLastChangedDate = (parse(cardInfoJs) \ "dateLastActivity").values.asInstanceOf[String]
                DateTime.parse(parsedLastChangedDate).getMillis
            }

            val period: Period = new Duration(startedRecordingAtMs, topicStartedAtMs.max).toPeriod
            val hoursMinutesAndSeconds = new PeriodFormatterBuilder()
              .printZeroAlways()
              .minimumPrintedDigits(2)
              .appendHours()
              .appendSeparator(":")
              .appendMinutes()
              .appendSeparator(":")
              .appendSeconds()
              .toFormatter
            val relativeStartStr = hoursMinutesAndSeconds.print(period)

            Theme(name, urls, relativeStartStr, topicStartedAtMs.max)
          }.sortWith((t1, t2) => t1.relativeStartMs <= t2.relativeStartMs)

          generateHtml(processedThemes)
        }
      }
    }
  }

  private def extractUrls(text: String): List[String] = {
    val containedUrls = new scala.collection.mutable.ArrayBuffer[String]()
    val urlRegex = "((https?|ftp|gopher|telnet|file):((//)|(\\\\))+[\\w\\d:#@%/;$()~_?\\+-=\\\\\\.&]*)"
    val pattern = Pattern.compile(urlRegex, Pattern.CASE_INSENSITIVE)
    val urlMatcher = pattern.matcher(text)

    while (urlMatcher.find()) {
      containedUrls.append(text.substring(urlMatcher.start(0), urlMatcher.end(0)))
    }

    containedUrls.toList
  }

  private def generateHtml(processed: List[Theme]): String = {
    val response = StringBuilder.newBuilder
    response.append("<ul>\n")
    processed.foreach { theme =>
      response.append(s"<li>[${theme.relativeStartReadableTime}] ")

      theme.urls.size match {
        case 0 =>
          response.append(s"${theme.title}</li>\n")
        case 1 =>
          response.append(s"""<a href="${theme.urls.head}">${theme.title}</a></li>\n""")
        case _ =>
          response.append(theme.title + "\n")
          response.append("<ul>\n")
          theme.urls.foreach { url =>
            response.append(s"""<li><a href="$url">$url</a></li>\n""")
          }
          response.append("</ul>\n")
          response.append("</li>\n")
      }
    }
    response.append("</ul>\n")
    response.toString
  }

  private def trelloHook = {
    path("trellohook" ~ Slash.?) {
      post {
        entity(as[String]) { json =>
          val event = parse(json)
          val eventType = (event \ "type").values.asInstanceOf[String]
          if ("updateCard" == eventType) {
            val listBeforeId = (event \ "action" \ "data" \ "listBefore" \ "id").values.asInstanceOf[String]
            val listAfterId = (event \ "action" \ "data" \ "listAfter" \ "id").values.asInstanceOf[String]

            if (Constants.TrelloToDiscussCurrentEpisodeListId == listBeforeId && Constants.TrelloInDiscussionListId == listAfterId) {
              val title = (event \ "action" \ "data" \ "card" \ "name").values.asInstanceOf[String]
              val cardId = (event \ "action" \ "data" \ "card" \ "id").values.asInstanceOf[String]

              val cardInfoJs = Request.Get(UrlGenerator.getCardInfoUrl(cardId)).execute().returnContent().asString()
              val parsedCardDesc = (parse(cardInfoJs) \ "desc").values.asInstanceOf[String]
              val urls = extractUrls(parsedCardDesc)

              Request
                .Post(UrlGenerator.postMessageToGitterChannel)
                .addHeader("Authorization", s"Bearer ${Config.GitterAccessToken}")
                .bodyString(
                  s"""
                     |{
                     |  "text" : "$title\n${urls.mkString("\n")}"                      |
                     |}
                    """.stripMargin, ContentType.APPLICATION_JSON)
                .execute().discardContent()
            }
          }

          complete {
            println("Sent message to Gitter")
            StatusCodes.OK
          }
        }
      } ~
        get {
          complete {
            StatusCodes.OK
          }
        }
    }
  }
}

object Constants {
  val TrelloDiscussedListId = "58a25bbd8d9dd346cdfbc209"
  val TrelloToDiscussCurrentEpisodeListId = "58a25bb3ee8b2c466ea64742"
  val TrelloInDiscussionListId = "58a25bb92cab934b5ee79fe8"
  val TrelloRecordingStartedCardId = "58a311747827da4eccd243e1"

  val GitterDevzenRoomId = "577e2d7fc2f0db084a21df2b"
}

object UrlGenerator {

  def getDiscussedListUrl: String = {
    s"https://api.trello.com/1/lists/${Constants.TrelloDiscussedListId}?" +
      s"fields=name&cards=open&card_fields=name,desc&key=${Config.TrelloApplicationKey}&token=${Config.TrelloReadToken}"
  }

  def getCardActionsUrl(id: String): String = {
    s"https://api.trello.com/1/cards/$id/actions?key=${Config.TrelloApplicationKey}&token=${Config.TrelloReadToken}"
  }

  def getCardInfoUrl(id: String): String = {
    s"https://api.trello.com/1/cards/$id?key=${Config.TrelloApplicationKey}&token=${Config.TrelloReadToken}"
  }

  def postMessageToGitterChannel: String = {
    s"https://api.gitter.im/v1/rooms/${Constants.GitterDevzenRoomId}/chatMessages"
  }

}

object Config {

  val TrelloApplicationKey = "a4a7b11c520772e330256e6c9fe0274b"
  val TrelloReadToken = "737c3884da123617b34e4e749c7c326e59fb8ec1887a7ef6c420dacdd82734a2"

  val GitterAccessToken = "461c74097fd21822e904ed80f35f4867df5a4c0a"

}