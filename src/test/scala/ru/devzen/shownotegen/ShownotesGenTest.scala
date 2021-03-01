package ru.devzen.shownotegen

import org.scalatest.funsuite.AnyFunSuite

class UrlUtilsTest extends AnyFunSuite {

  test("Extract 2 urls") {
    val trelloDesc = " --- Hello  foo bar, test - " +
      "https://www.theengineeringmanager.com/management-101/performance-reviews/ " +
      "https://blog.pragmaticengineer.com/performance-reviews-for-software-engineers/ "
    val actualUrls = UrlUtils.extractUrls(trelloDesc)
    assert(actualUrls.size == 2)
    assert(actualUrls.head == "https://www.theengineeringmanager.com/management-101/performance-reviews/")
    assert(actualUrls(1) == "https://blog.pragmaticengineer.com/performance-reviews-for-software-engineers/")
  }
}

class UrlGeneratorTest extends AnyFunSuite {

  test("Generate one complex TG url") {
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
}