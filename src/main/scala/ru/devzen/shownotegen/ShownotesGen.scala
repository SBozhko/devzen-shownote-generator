package ru.devzen.shownotegen

import java.util.regex.Pattern

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.Directives.get
import akka.http.scaladsl.server.Directives.path
import akka.http.scaladsl.server.PathMatchers.LongNumber
import akka.stream.ActorMaterializer
import org.apache.http.client.fluent.Request
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

    implicit val system = ActorSystem("shownotegenerator")
    implicit val materializer = ActorMaterializer()
    val routes = getRoute
    Http().bindAndHandle(routes, "0.0.0.0", Properties.envOrElse("PORT", "9025").toInt)
    println(s"Server online")
  }

  private def getRoute = {
    pathPrefix("generate") {
      (get & path(LongNumber)) { startedRecordingAtMs =>
        complete {
          val discussedCardsJs = Request.Get(UrlGenerator.getDiscussedListUrl).execute().returnContent().asString()
          val cards = parse(discussedCardsJs) \ "cards"

          val processedThemes = cards.children.map { card =>
            val cardId = (card \ "id").values.asInstanceOf[String]
            val name = (card \ "name").values.asInstanceOf[String]
            val desc = (card \ "desc").values.asInstanceOf[String]

            val urls = extractUrls(desc)

            val cardInfoJs = Request.Get(UrlGenerator.getCardInfoUrl(cardId)).execute().returnContent().asString()

            val dragAndDropEvents = parse(cardInfoJs).children.filter { event =>
              val eventType = (event \ "type").values.asInstanceOf[String]
              "updateCard" == eventType
            }

            val topicStartedAtMs = dragAndDropEvents.flatMap { event =>
              val listAfterId = (event \ "data" \ "listAfter" \ "id").values.asInstanceOf[String]
              val listBeforeId = (event \ "data" \ "listBefore" \ "id").values.asInstanceOf[String]

              if (Constants.ToDiscussCurrentEpisodeListId == listBeforeId && Constants.InDiscussionListId == listAfterId) {
                val dateString = (event \ "date").values.asInstanceOf[String]
                Some(DateTime.parse(dateString).getMillis)
              } else None
            }

            if (topicStartedAtMs.size != 1) {
              println("WARN - Unexpected movements of a card from 'to discuss' to 'in discussion'")
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
}

object Constants {
  val DiscussedListId = "58a25bbd8d9dd346cdfbc209"
  val ToDiscussCurrentEpisodeListId = "58a25bb3ee8b2c466ea64742"
  val InDiscussionListId = "58a25bb92cab934b5ee79fe8"
}

object UrlGenerator {

  def getDiscussedListUrl: String = {
    s"https://api.trello.com/1/lists/${Constants.DiscussedListId}?" +
      s"fields=name&cards=open&card_fields=name,desc&key=${Config.ApiKey}&token=${Config.ReadToken}"
  }

  def getCardInfoUrl(id: String): String = {
    s"https://api.trello.com/1/cards/$id/actions?key=${Config.ApiKey}&token=${Config.ReadToken}"
  }

}

object Config {

  val ApiKey = "a4a7b11c520772e330256e6c9fe0274b"
  val ReadToken = "737c3884da123617b34e4e749c7c326e59fb8ec1887a7ef6c420dacdd82734a2"

}