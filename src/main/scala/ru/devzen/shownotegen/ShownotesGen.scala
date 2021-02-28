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
import org.apache.commons.lang3.StringEscapeUtils
import org.apache.http.client.fluent.Request
import org.apache.http.entity.ContentType
import org.joda.time.DateTime
import org.joda.time.Duration
import org.joda.time.format.PeriodFormatterBuilder
import org.json4s.JsonDSL._
import org.json4s._
import org.json4s.jackson.JsonMethods._

import scala.util.{Properties, Success, Try}

case class Theme(title: String, urls: List[String], readableStartTime: String, relativeStartMs: Long)

object ShownotesGen {

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("shownotegenerator")
    implicit val materializer = ActorMaterializer()
    val routes = generateShownotes ~ trelloHook
    Http().bindAndHandle(routes, "0.0.0.0", Properties.envOrElse("PORT", "9025").toInt)
    println(s"Server online")
  }

  private def generateShownotes = {
    (path("generate" ~ Slash.?) & get) {
      parameters('start.as[Long] ?) { manualStartTimeOpt =>
        try {
          val discussedCardsJs = Request.Get(UrlGenerator.getDiscussedListUrl).execute().returnContent().asString()
          val cards = parse(discussedCardsJs) \ "cards"

          val processedThemes = cards.children.map { card =>
            val cardId = (card \ "id").values.asInstanceOf[String]
            val name = (card \ "name").values.asInstanceOf[String]
            val desc = (card \ "desc").values.asInstanceOf[String]

            getTimestampOfThemeStartedEvent(cardId) match {
              case Some(startedDiscussionAtMs) =>
                val startedRecordingAtMs = getTimestampOfRecordingStartedEvent(manualStartTimeOpt)
                val relativeStartStr = getHumanReadableTimestamp(startedRecordingAtMs, startedDiscussionAtMs)

                Theme(name, extractUrls(desc), relativeStartStr, startedDiscussionAtMs)
              case None =>
                Theme(name, extractUrls(desc), "TIMESTAMP_IS_MISSING", 0)
            }

          }.sortWith((t1, t2) => t1.relativeStartMs <= t2.relativeStartMs)

          complete(generateHtml(processedThemes))
        } catch {
          case e: Exception =>
            e.printStackTrace()
            complete(StatusCodes.InternalServerError, e.getMessage)
        }
      }
    }
  }

  private def getHumanReadableTimestamp(startedRecordingAtMs: Long, startedDiscussionAtMs: Long): String = {
    val timestampFmt = new PeriodFormatterBuilder()
      .printZeroAlways()
      .minimumPrintedDigits(2)
      .appendHours()
      .appendSeparator(":")
      .appendMinutes()
      .appendSeparator(":")
      .appendSeconds()
      .toFormatter
    val duration = new Duration(startedRecordingAtMs, startedDiscussionAtMs)
    timestampFmt.print(duration.toPeriod)
  }

  private def getTimestampOfRecordingStartedEvent(manualStartTimeOpt: Option[Long]): Long = {
    manualStartTimeOpt match {
      case Some(manualStartTime) => manualStartTime
      case None =>
        val cardInfoJs = Request
          .Get(UrlGenerator.getCardInfoUrl(Constants.TrelloRecordingStartedCardId))
          .execute().returnContent().asString()
        val parsedLastChangedDate = (parse(cardInfoJs) \ "dateLastActivity").values.asInstanceOf[String]
        DateTime.parse(parsedLastChangedDate).getMillis
    }
  }

  private def getTimestampOfThemeStartedEvent(cardId: String): Option[Long] = {
    val cardActionsJs = Request.Get(UrlGenerator.getCardActionsUrl(cardId)).execute().returnContent().asString()
    val possibleStartTimestamps = parse(cardActionsJs).children.flatMap { event =>
      (event \ "type").values.asInstanceOf[String] match {
        case "updateCard" =>
          val listAfterId = (event \ "data" \ "listAfter" \ "id").values.asInstanceOf[String]
          val listBeforeId = (event \ "data" \ "listBefore" \ "id").values.asInstanceOf[String]

          (listBeforeId, listAfterId) match {
            case pairOfIds
              if (pairOfIds == (Constants.TrelloToDiscussCurrentEpisodeListId, Constants.TrelloInDiscussionListId))
                || pairOfIds == (Constants.TrelloBacklogListId, Constants.TrelloInDiscussionListId) =>
              val dateString = (event \ "date").values.asInstanceOf[String]
              Some(DateTime.parse(dateString).getMillis)
            case _ => None
          }
        case _ => None
      }
    }

    possibleStartTimestamps match {
      case _ if possibleStartTimestamps.size == 1 =>
        Some(possibleStartTimestamps.head)
      case _ if possibleStartTimestamps.size > 1 =>
        println(s"WARN - More than one card movement of $cardId 'to discuss' -> 'in discussion'. Using the latest timestamp.")
        Some(possibleStartTimestamps.max)
      case _ if possibleStartTimestamps.isEmpty =>
        println(s"WARN - Can't fined card movements of $cardId 'to discuss' -> 'in discussion'. No timestamp found.")
        None
    }
  }

  private def generateHtml(processed: List[Theme]): String = {
    val escapedThemes = processed.map(theme => theme.copy(title = StringEscapeUtils.escapeHtml4(theme.title)))
    val response = StringBuilder.newBuilder.append("<ul>\n")

    escapedThemes.foreach { theme =>
      response.append(s"<li>[${theme.readableStartTime}] ")

      theme.urls.size match {
        case 0 =>
          response.append(s"${theme.title}</li>\n")
        case 1 =>
          response.append(s"""<a href="${theme.urls.head}">${theme.title}</a></li>\n""")
        case _ =>
          response.append(theme.title + "\n")
          response.append("<ul>\n")
          theme.urls.foreach { url =>
            val urlTitle = UrlUtils.titleOf(url)
            response.append(s"""<li><a href="$url">$urlTitle</a></li>\n""")
          }
          response.append("</ul>\n")
          response.append("</li>\n")
      }
    }
    response.append("</ul>\n").toString
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

  private def trelloHook = {
    path("trellohook" ~ Slash.?) {
      post {
        entity(as[String]) { json =>
          try {
            println(json)
            val event = parse(json)
            val actionType = (event \ "action" \ "type").values.asInstanceOf[String]

            if ("updateCard" == actionType) {
              val listBeforeId = (event \ "action" \ "data" \ "listBefore" \ "id").values.asInstanceOf[String]
              val listAfterId = (event \ "action" \ "data" \ "listAfter" \ "id").values.asInstanceOf[String]

              if (Constants.TrelloToDiscussCurrentEpisodeListId == listBeforeId && Constants.TrelloInDiscussionListId == listAfterId) {
                val title = (event \ "action" \ "data" \ "card" \ "name").values.asInstanceOf[String]
                val cardId = (event \ "action" \ "data" \ "card" \ "id").values.asInstanceOf[String]
                val urls = getThemeUrlsByCardId(cardId)
                postMessageToGitter(title, urls)
                postMessageToTelegram(title, urls)
              }
            }
          } catch {
            case e: Exception =>
              e.printStackTrace()
          }
          complete(StatusCodes.OK)
        }
      }
    } ~
      get {
        complete(StatusCodes.OK)
      }
  }

  private def getThemeUrlsByCardId(cardId: String): List[String] = {
    val cardInfoJs = Request.Get(UrlGenerator.getCardInfoUrl(cardId)).execute().returnContent().asString()
    val parsedCardDesc = (parse(cardInfoJs) \ "desc").values.asInstanceOf[String]
    val urls = extractUrls(parsedCardDesc)
    urls
  }

  private def postMessageToGitter(title: String, urls: List[String]): Unit = {
    val urlsAsString = urls.mkString("\n")
    val jsonBody = compact(render("text" -> s"$title\n$urlsAsString"))
    val response = Request
      .Post(UrlGenerator.postMessageToGitterChannel)
      .addHeader("Authorization", s"Bearer ${Config.GitterAccessToken}")
      .bodyString(jsonBody, ContentType.APPLICATION_JSON)
      .execute().returnResponse()
    if (response.getStatusLine.getStatusCode != 200) {
      println(s"Can't send message to Gitter. Gitter response:\n$response")
    } else {
      println(s"Sent message to Gitter successfully")
    }
  }

  private def postMessageToTelegram(title: String, urls: List[String]): Unit = {
    val urlsAsString = urls.mkString("\n")
    val wholeMessage = s"$title\n$urlsAsString"
    val response = Request
      .Get(UrlGenerator.sendMessageToTelegramChannel(wholeMessage))
      .execute().returnResponse()
    if (response.getStatusLine.getStatusCode != 200) {
      println(s"Can't send message to Telegram. Telegram response:\n$response")
    } else {
      println(s"Sent message to Telegram successfully")
    }
  }
}

object Constants {
  val TrelloDiscussedListId = Properties.envOrElse("TRELLO_DISCUSSED_LIST_ID", "")
  val TrelloToDiscussCurrentEpisodeListId = Properties.envOrElse("TRELLO_TO_DISCUSS_LIST_ID", "")
  val TrelloInDiscussionListId = Properties.envOrElse("TRELLO_IN_DISCUSSION_LIST_ID", "")
  val TrelloRecordingStartedCardId = Properties.envOrElse("TRELLO_RECORDING_STARTED_CARD_ID", "")
  val TrelloBacklogListId = Properties.envOrElse("TRELLO_BACKLOG_LIST_ID", "")

  val GitterDevzenRoomId = Properties.envOrElse("GITTER_DEVZEN_ROOM_ID", "")

  val TelegramBotToken = Properties.envOrElse("TELEGRAM_BOT_TOKEN", "")
  val TelegramDevZenChannel = "@devzen_live"
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

  def sendMessageToTelegramChannel(message: String): String = {
      s"https://api.telegram.org/bot${Constants.TelegramBotToken}/sendMessage?chat_id=${Constants.TelegramBotToken}&text=${message}"
  }

}

object Config {

  val TrelloApplicationKey = Properties.envOrElse("TRELLO_APP_KEY", "")
  val TrelloReadToken = Properties.envOrElse("TRELLO_READ_TOKEN", "")

  val GitterAccessToken = Properties.envOrElse("GITTER_ACCESS_TOKEN", "")

}

object UrlUtils {
  private val TitleTagPattern = Pattern.compile("<\\s*title.*>(.*)<\\/title>")

  def titleOf(url: String): String = {

    Try(Request.Get(url).execute().returnContent().asString()) match {
      case Success(content) =>
        val TagMatcher = TitleTagPattern.matcher(content)
        if (TagMatcher.find()) {
          Try(TagMatcher.group(1)).getOrElse(url)
        } else {
          url
        }
      case _ => url
    }
  }
}