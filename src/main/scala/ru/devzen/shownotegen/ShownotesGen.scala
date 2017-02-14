package ru.devzen.shownotegen

import java.util.regex.Pattern

import org.apache.http.client.fluent.Request
import org.joda.time.DateTime
import org.joda.time.Duration
import org.joda.time.Period
import org.joda.time.format.PeriodFormatterBuilder
import org.json4s._
import org.json4s.jackson.JsonMethods._

case class Theme(title: String, urls: List[String], relativeStartReadableTime: String, relativeStartMs: Long)

object ShownotesGen {

  def main(args: Array[String]): Unit = {

    val startedRecordingAtMs = 1487069570000l

    val discussedCardsJs = Request.Get(UrlGenerator.getDiscussedListUrl).execute().returnContent().asString()
    val cards = parse(discussedCardsJs) \ "cards"

    val processed = cards.children.map { card =>
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

    println("<ul>")
    processed.foreach { theme =>
      print(s"<li>[${theme.relativeStartReadableTime}] ")

      theme.urls.size match {
        case 0 =>
          println(s"${theme.title}</li>")
        case 1 =>
          println(s"""<a href="${theme.urls.head}">${theme.title}</a></li>""")
        case _ =>
          println(theme.title)
          println("<ul>")
          theme.urls.foreach { url =>
            println(s"""<li><a href="$url">$url</a></li>""")
          }
          println("</ul>")
          println("</li>")
      }
    }
    println("</ul>")

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