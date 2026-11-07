package com.ehviewer.shared

import androidx.compose.ui.graphics.toPixelMap
import com.ehviewer.core.domain.reader.ArchiveDocument
import com.ehviewer.core.domain.reader.ArchiveException
import com.ehviewer.core.domain.reader.ArchiveFailure
import kotlin.io.encoding.Base64

/** Generated owned PNG/ZIP fixture, used only by explicit regressions, never normal imports. */
fun archiveProductFixture(): ByteArray = Base64.decode(
    "UEsDBAoAAAgAAAAAIQDh2/GzRwAAAEcAAAAGAAkAMTAucG5nVVQFAAEAAAAAiVBORw0KGgoAAAANSUhEUgAAAAEAAAACCAYAAACZgbYnAAAADklEQVR4XmNg+A+CQAAAF/IF+w/LXpgAAAAASUVORK5CYIJQSwMECgAACAAAAAAhANWroC9HAAAARwAAAAUACQAyLnBuZ1VUBQABAAAAAIlQTkcNChoKAAAADUlIRFIAAAACAAAAAQgGAAAA9CJ/igAAAA5JREFUeJxj+M/AAEL/AQ/5A/2FEZl2AAAAAElFTkSuQmCCUEsDBAoAAAgAAAAAIQDAI1YcHgAAAB4AAAAKAAkAUkVBRE1FLnR4dFVUBQABAAAAAEdlbmVyYXRlZCBFaFZpZXdlciB0ZXN0IGltYWdlc1BLAQIKAAoAAAgAAAAAIQDh2/GzRwAAAEcAAAAGAAkAAAAAAAAAAAAAAAAAAAAxMC5wbmdVVAUAAQAAAABQSwECCgAKAAAIAAAAACEA1augL0cAAABHAAAABQAJAAAAAAAAAAAAAAB0AAAAMi5wbmdVVAUAAQAAAABQSwECCgAKAAAIAAAAACEAwCNWHB4AAAAeAAAACgAJAAAAAAAAAAAAAADnAAAAUkVBRE1FLnR4dFVUBQABAAAAAFBLBQYAAAAAAwADALoAAAA2AQAAAAA=",
)

/** Byte-identical EhViewer AES ZIP regression from baseline 8fdfb6c; intentionally tiny text pages. */
fun encryptedArchiveProductFixture(): ByteArray = Base64.decode(
    "UEsDBDMAAQBjAMFQSl0AAAAANAAAABgAAAAJAAsAVVBQRVIuSlBHAZkHAAIAQUUDAADwfsouL3qTl2g2vXju8FakOeg7Dy4JpvkuBOu3J+FJHQENviQL45pQpJoZ6IX56Ik0lBQZUEsDBDMAAQBjAMFQSl0AAAAAKAAAAAwAAAALAAsAaWdub3JlZC50eHQBmQcAAgBBRQMAAG5du/voqd41TGx+WiF32EwzX6/c9q31+1KLt58cblTBUfj1AWptkVlQSwMEMwABAGMAwVBKXQAAAAAfAAAAAwAAAAkACwBwYWdlMS5naWYBmQcAAgBBRQMAALPZj+esrhhUka+o3slI6Ano7+ezU2jdY4Ib+C2Oha5QSwMEMwABAGMAwVBKXQAAAAAfAAAAAwAAAAoACwBwYWdlMTAucG5nAZkHAAIAQUUDAABQGguYfOnuAdK0A8S+uWumdEkL8D/ztDf7p5fq9Yc5UEsDBDMAAQBjAMFQSl0AAAAAHwAAAAMAAAAJAAsAcGFnZTIuanBnAZkHAAIAQUUDAACO7KKN0Xgua4gkx21YZgiiZucGJ+TvO+PRxJqfH2TOUEsBAj8DMwABAGMAwVBKXQAAAAA0AAAAGAAAAAkALwAAAAAAAAAggKSBAAAAAFVQUEVSLkpQRwoAIAAAAAAAAQAYAHS6HOVbWN0BAAAAAAAAAAAAAAAAAAAAAAGZBwACAEFFAwAAUEsBAj8DMwABAGMAwVBKXQAAAAAoAAAADAAAAAsALwAAAAAAAAAggKSBZgAAAGlnbm9yZWQudHh0CgAgAAAAAAABABgAwrcc5VtY3QEAAAAAAAAAAAAAAAAAAAAAAZkHAAIAQUUDAABQSwECPwMzAAEAYwDBUEpdAAAAAB8AAAADAAAACQAvAAAAAAAAACCApIHCAAAAcGFnZTEuZ2lmCgAgAAAAAAABABgAdLUc5VtY3QEAAAAAAAAAAAAAAAAAAAAAAZkHAAIAQUUDAABQSwECPwMzAAEAYwDBUEpdAAAAAB8AAAADAAAACgAvAAAAAAAAACCApIETAQAAcGFnZTEwLnBuZwoAIAAAAAAAAQAYAKKwHOVbWN0BAAAAAAAAAAAAAAAAAAAAAAGZBwACAEFFAwAAUEsBAj8DMwABAGMAwVBKXQAAAAAfAAAAAwAAAAkALwAAAAAAAAAggKSBZQEAAHBhZ2UyLmpwZwoAIAAAAAAAAQAYAAyzHOVbWN0BAAAAAAAAAAAAAAAAAAAAAAGZBwACAEFFAwAAUEsFBgAAAAAFAAUAAQIAALYBAAAAAA==",
)

/** Takes ownership and checks real decryption, natural order and retention of an accepted password. */
fun verifyArchivePassword(document: ArchiveDocument) {
    document.use {
        check(it.pageCount == 3 && it.needsPassword)
        check(runCatching { it.read(0) }.exceptionOrNull() is ArchiveException)
        check(!it.providePassword("wrong"))
        check(it.providePassword("baseline"))
        check(!it.providePassword("wrong-again"))
        listOf("one", "two", "ten").forEachIndexed { index, expected -> check(it.read(index).contentEquals(expected.encodeToByteArray())) }
    }
}

/** Takes ownership; prove real natural order, decoded pixels and closed-handle rejection. */
fun verifyArchiveProduct(document: ArchiveDocument): Int {
    document.use {
        check(it.pageCount == 2 && !it.needsPassword)
        check(it.extension(0) == "png" && it.extension(1) == "png")
        val first = decodePlatformImage(it.read(0))
        check(first.width == 2 && first.height == 1)
        val colors = first.toPixelMap()
        check(colors[0, 0].red > 0.99f && colors[0, 0].blue < 0.01f && colors[1, 0].blue > 0.99f)
        val second = decodePlatformImage(it.read(1))
        check(second.width == 1 && second.height == 2)
        check(second.toPixelMap()[0, 0].green > 0.99f && second.toPixelMap()[0, 0].red < 0.01f)
    }
    document.close()
    val closed = runCatching { document.read(0) }.exceptionOrNull()
    check(closed is ArchiveException && closed.failure == ArchiveFailure.CLOSED)
    return 3
}
