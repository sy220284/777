package com.labteto.dshmobile.uicontract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiAttachmentMediaPreviewTest {
    @Test fun nativeMediaKindsAreExplicitlyAdmitted() {
        assertEquals(UiAttachmentMediaKind.IMAGE, uiAttachmentMediaKind("image/png"))
        assertEquals(UiAttachmentMediaKind.IMAGE, uiAttachmentMediaKind("image/webp"))
        assertEquals(UiAttachmentMediaKind.AUDIO, uiAttachmentMediaKind("audio/mpeg"))
        assertEquals(UiAttachmentMediaKind.AUDIO, uiAttachmentMediaKind("audio/ogg"))
        assertEquals(UiAttachmentMediaKind.VIDEO, uiAttachmentMediaKind("video/mp4"))
        assertEquals(UiAttachmentMediaKind.VIDEO, uiAttachmentMediaKind("video/webm"))
    }

    @Test fun svgAndUnknownBinaryFormatsNeverEnterTheMediaDecoder() {
        assertNull(uiAttachmentMediaKind("image/svg+xml"))
        assertNull(uiAttachmentMediaKind("application/octet-stream"))
        assertNull(uiAttachmentMediaKind("text/plain"))
        assertNull(uiAttachmentMediaKind(""))
    }

    @Test fun mediaMimeParametersDoNotChangeTheDecoderCategory() {
        assertEquals(UiAttachmentMediaKind.AUDIO,
            uiAttachmentMediaKind("audio/mpeg; charset=binary"))
        assertEquals(UiAttachmentMediaKind.VIDEO,
            uiAttachmentMediaKind("video/mp4; codecs=avc1"))
    }
}
