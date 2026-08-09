package com.streamy.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DjProtocolParseTest {
    @Test fun parses_ultravox() = assertEquals(DjProtocol.ULTRAVOX, DjProtocol.parse("ULTRAVOX"))
    @Test fun parses_icecast() = assertEquals(DjProtocol.ICECAST, DjProtocol.parse("ICECAST"))
    @Test fun case_insensitive() = assertEquals(DjProtocol.ICECAST, DjProtocol.parse("icecast"))
    @Test fun old_auto_normalizes_to_ultravox() = assertEquals(DjProtocol.ULTRAVOX, DjProtocol.parse("AUTO"))
    @Test fun blank_defaults_ultravox() = assertEquals(DjProtocol.ULTRAVOX, DjProtocol.parse(""))
    @Test fun garbage_defaults_ultravox() = assertEquals(DjProtocol.ULTRAVOX, DjProtocol.parse("nope"))
}
