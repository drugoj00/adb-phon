package app.adbphon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Приглашения Tailscale: формирование запроса и разбор ответов на подменённом транспорте (настоящая сеть и настоящие токены не используются). */
class MiscTsInviteTest {
    private val fakeToken = "tskey-api-TESTONLY-0000000000"

    private class Rec(val code: Int, val resp: String?) : TsInviteApi.Http {
        var url: String? = null; var headers: Map<String, String>? = null; var body: String? = null; var calls = 0
        override fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String?> {
            calls++; this.url = url; this.headers = headers; this.body = body; return code to resp
        }
    }

    @Test fun requestShape() {
        val r = Rec(200, """[{"id":"1","role":"member","tailnetId":"2","inviterId":"3","email":"friend@example.com","lastEmailSentAt":"2026-11-15T10:00:00Z","inviteUrl":"https://login.tailscale.com/uinv/abc"}]""")
        val res = TsInviteApi.send(r, " $fakeToken ", "  friend@example.com ")
        assertEquals("https://api.tailscale.com/api/v2/tailnet/-/user-invites", r.url)
        assertEquals("""[{"email":"friend@example.com","role":"member"}]""", r.body)
        assertEquals("Bearer $fakeToken", r.headers!!["Authorization"])
        assertEquals("application/json", r.headers!!["Content-Type"])
        assertEquals(TsInviteApi.Result.Sent("friend@example.com", "https://login.tailscale.com/uinv/abc", true), res)
    }

    @Test fun sentWithoutEmailTime() {
        val res = TsInviteApi.parse(200, """[{"id":"1","email":"a@b.cd","lastEmailSentAt":"0001-01-01T00:00:00Z"}]""", "a@b.cd")
        assertEquals(TsInviteApi.Result.Sent("a@b.cd", null, false), res)
    }

    @Test fun badEmailAndTokenNoNetwork() {
        val r = Rec(200, "[]")
        assertEquals("email", (TsInviteApi.send(r, fakeToken, "not-an-email") as TsInviteApi.Result.Error).kind)
        assertEquals("email", (TsInviteApi.send(r, fakeToken, "a b@c.de") as TsInviteApi.Result.Error).kind)
        assertEquals("token", (TsInviteApi.send(r, null, "a@b.cd") as TsInviteApi.Result.Error).kind)
        assertEquals("token", (TsInviteApi.send(r, "short", "a@b.cd") as TsInviteApi.Result.Error).kind)
        assertEquals("token", (TsInviteApi.send(r, "tskey api with spaces", "a@b.cd") as TsInviteApi.Result.Error).kind)
        assertEquals(0, r.calls)
    }

    @Test fun errorCodes() {
        fun k(code: Int, body: String? = """{"message":"x"}""") = (TsInviteApi.parse(code, body, "a@b.cd") as TsInviteApi.Result.Error).kind
        assertEquals("auth", k(401))
        assertEquals("forbidden", k(403))
        assertEquals("notfound", k(404))
        assertEquals("exists", k(409))
        assertEquals("bad", k(400))
        assertEquals("exists", k(400, """{"message":"user already invited"}"""))
        assertEquals("rate", k(429))
        assertEquals("server", k(500))
        assertEquals("server", k(503, null))
        assertEquals("other", k(418))
        assertEquals("other", k(200, "<html>proxy</html>"))
    }

    @Test fun messageExtractedAndTokenMasked() {
        val e = TsInviteApi.parse(401, """{"message":"API token invalid: $fakeToken \"bad\""}""", "a@b.cd", fakeToken) as TsInviteApi.Result.Error
        assertEquals(401, e.http)
        assertEquals("API token invalid: *** \"bad\"", e.message)
        assertFalse(e.message!!.contains("TESTONLY"))
    }

    @Test fun networkFailure() {
        val boom = TsInviteApi.Http { _, _, _ -> throw java.io.IOException("no route") }
        val e = TsInviteApi.send(boom, fakeToken, "a@b.cd") as TsInviteApi.Result.Error
        assertEquals("net", e.kind); assertEquals(0, e.http)
    }

    @Test fun jsonEscaping() {
        assertEquals("""[{"email":"a\"b\\c@d.ef","role":"member"}]""", TsInviteApi.body("a\"b\\c@d.ef"))
        assertEquals("line\nnext é", TsInviteApi.jsonField("""{"message":"line\nnext é"}""", "message"))
        assertNull(TsInviteApi.jsonField("""{"other":"x"}""", "message"))
    }

    @Test fun emailValidation() {
        assertTrue(TsInviteApi.validEmail("first.last+tag@sub.example.co.uk"))
        assertFalse(TsInviteApi.validEmail("@example.com"))
        assertFalse(TsInviteApi.validEmail("user@localhost"))
        assertFalse(TsInviteApi.validEmail("user@-bad.com"))
    }
}
