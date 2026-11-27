package app.adbphon

import org.junit.Assert.assertEquals
import org.junit.Test

class PapirusImeTest {
    @Test fun noPackage() = assertEquals(PapirusIme.Result.NOT_INSTALLED, PapirusIme.parse("PAPIRUS_NONE\n"))
    @Test fun turnedOn() = assertEquals(PapirusIme.Result.ON, PapirusIme.parse("Input method app.papirus/.KeyboardService: now enabled for user #0\nPAPIRUS_DEF=app.papirus/.KeyboardService\n"))
    @Test fun otherKeyboardStayed() = assertEquals(PapirusIme.Result.FAILED, PapirusIme.parse("Error: not allowed\nPAPIRUS_DEF=com.example/.Ime\n"))
    @Test fun emptyAnswer() = assertEquals(PapirusIme.Result.FAILED, PapirusIme.parse(""))
    @Test fun onlyOurSignature() {
        assertEquals(true, PapirusIme.sameSigner("7F:9C:98:8E:14:FA:6A:D7:BE:B7:52:6D:6B:50:D6:5B:59:72:22:04:17:64:1E:1A:2A:9D:C0:BC:87:B6:19:8E"))
        assertEquals(false, PapirusIme.sameSigner("47164558d82446c5d1ac92c90eaa1d233993ea65cb00eb00e52f140adf1e4dfa"))
        assertEquals(false, PapirusIme.sameSigner(null))
    }
    @Test fun installAnswer() {
        assertEquals(true, PapirusIme.installed("Success\n"))
        assertEquals(false, PapirusIme.installed("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]\n"))
    }
}
