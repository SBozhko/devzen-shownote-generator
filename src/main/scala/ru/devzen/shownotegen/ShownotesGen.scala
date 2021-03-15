package ru.devzen.shownotegen

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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

    println(s"Running tests")

    // run tests this hacky way because I couldn't make ScalaTest work on Heroku
    Tests.extractTwoUrls()
    Tests.generateOneComplexTelegramUrl()
    Tests.getTitleForMediumPost()
    Tests.getTitleForAmazon()

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

                Theme(name, UrlUtils.extractUrls(desc), relativeStartStr, startedDiscussionAtMs)
              case None =>
                Theme(name, UrlUtils.extractUrls(desc), "TIMESTAMP_IS_MISSING", 0)
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
    val urls = UrlUtils.extractUrls(parsedCardDesc)
    urls
  }

  private def postMessageToTelegram(title: String, urls: List[String]): Unit = {
    val urlsAsString = urls.mkString("\n")
    val wholeMessage = s"$title\n$urlsAsString"
    val response = Request
      .Get(UrlGenerator.sendMessageToTelegramChannel(wholeMessage, Constants.TelegramBotToken, Constants.TelegramDevZenChannel))
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

  def sendMessageToTelegramChannel(message: String, botToken: String, channel: String): String = {
    val escapedMessage = URLEncoder.encode(message, StandardCharsets.UTF_8.name())
    val url = s"https://api.telegram.org/bot$botToken/sendMessage?chat_id=$channel&text=$escapedMessage"
    println("sendMessageToTelegramChannel URL: " + url)
    url
  }

}

object Config {

  val TrelloApplicationKey = Properties.envOrElse("TRELLO_APP_KEY", "")
  val TrelloReadToken = Properties.envOrElse("TRELLO_READ_TOKEN", "")

}

object UrlUtils {
  private val TitleTagPattern = Pattern.compile("<\\s*title.*>(.*)<\\/title>")

  def titleOf(url: String): String = {

    Try(Request.Get(url).execute().returnContent().asString()) match {
      case Success(content) =>
        val TagMatcher = TitleTagPattern.matcher(content)
        if (TagMatcher.find()) {
          val str = TagMatcher.group(1)
          Try(str).getOrElse(url)
        } else {
          url
        }
      case _ => url
    }
  }

  def extractUrls(text: String): List[String] = {
    val containedUrls = new scala.collection.mutable.ArrayBuffer[String]()
    val urlRegex = "((https?|ftp|gopher|telnet|file):((//)|(\\\\))+[\\w\\d:#@%/;$()~_?\\+-=\\\\\\.&]*)"
    val pattern = Pattern.compile(urlRegex, Pattern.CASE_INSENSITIVE)
    val urlMatcher = pattern.matcher(text)

    while (urlMatcher.find()) {
      containedUrls.append(text.substring(urlMatcher.start(0), urlMatcher.end(0)))
    }

    containedUrls.toList
  }
}

object Tests {
  def extractTwoUrls(): Unit = {
    val trelloDesc = " --- Hello  foo bar, test - " +
      "https://www.theengineeringmanager.com/management-101/performance-reviews/ " +
      "https://blog.pragmaticengineer.com/performance-reviews-for-software-engineers/ "
    val actualUrls = UrlUtils.extractUrls(trelloDesc)
    assert(actualUrls.size == 2)
    assert(actualUrls.head == "https://www.theengineeringmanager.com/management-101/performance-reviews/")
    assert(actualUrls(1) == "https://blog.pragmaticengineer.com/performance-reviews-for-software-engineers/")
  }

  def generateOneComplexTelegramUrl(): Unit = {
    val actual = UrlGenerator.sendMessageToTelegramChannel("Книжный Клуб. Глава 4 книги \"The Manager's Path\". В этот раз кратко.\n" +
      "https://amzn.to/2Jnkbs2\nhttps://medium.com/@mrabkin/the-art-of-the-awkward-1-1-f4e1dcbd1c5c\n" +
      "https://noidea.dog/glue\nhttps://www.youtube.com/watch?v=DTAXQNJLskk\n" +
      "https://www.amazon.co.uk/Making-Manager-What-Everyone-Looks/dp/0753552892/\n" +
      "https://www.nonviolentcommunication.com/", "123", "@devzen_live")
    assert(actual == "https://api.telegram.org/bot123/sendMessage?chat_id=@devzen_live&text=%D0%9A%D0%BD%D0%B8%D0%B6%" +
      "D0%BD%D1%8B%D0%B9+%D0%9A%D0%BB%D1%83%D0%B1.+%D0%93%D0%BB%D0%B0%D0%B2%D0%B0+4+%D0%BA%D0%BD%D0%B8%D0%B3%D0%B8+%22" +
      "The+Manager%27s+Path%22.+%D0%92+%D1%8D%D1%82%D0%BE%D1%82+%D1%80%D0%B0%D0%B7+%D0%BA%D1%80%D0%B0%D1%82%D0%BA%D0%BE.%0A" +
      "https%3A%2F%2Famzn.to%2F2Jnkbs2%0Ahttps%3A%2F%2Fmedium.com%2F%40mrabkin%2Fthe-art-of-the-awkward-1-1-f4e1dcbd1c5c%0A" +
      "https%3A%2F%2Fnoidea.dog%2Fglue%0Ahttps%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DDTAXQNJLskk%0A" +
      "https%3A%2F%2Fwww.amazon.co.uk%2FMaking-Manager-What-Everyone-Looks%2Fdp%2F0753552892%2F%0A" +
      "https%3A%2F%2Fwww.nonviolentcommunication.com%2F")
  }

  def getTitleForMediumPost(): Unit = {
    val actual = UrlUtils.titleOf("https://medium.com/better-programming/boost-your-command-line-productivity-with-fuzzy-finder-985aa162ba5d")
    assert(!actual.startsWith("https://medium.com"))

  }

  def getTitleForAmazon() = {
    val actual = UrlUtils.titleOf("https://www.amazon.co.uk/Making-Manager-What-Everyone-Looks/dp/0753552892/")
    assert(!actual.startsWith("https://www.amazon.co.uk"))
  }

}