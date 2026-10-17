// 本文件由 tools/generate_tokens.py 从 tokens.json 生成，禁止手改
package com.labteto.dshmobile.uipack.zhaoxi.theme

import androidx.compose.ui.graphics.Color

object ZhaoxiTokens {
    const val VERSION = "E8.9"

    object Neutral {
        object Light {
            val bg = Color(0xFFFFFFFF)
            val bg_2 = Color(0xFFF7F7F7)
            val side = Color(0xFFF9F9F9)
            val card = Color(0xFFFFFFFF)
            val ink = Color(0xFF0D0D0D)
            val ink_2 = Color(0xFF6B7280)
            val ink_3 = Color(0xFF94949D)
        }
        object Dark {
            val bg = Color(0xFF171717)
            val bg_2 = Color(0xFF212121)
            val side = Color(0xFF1B1B1B)
            val card = Color(0xFF212121)
            val ink = Color(0xFFECECEC)
            val ink_2 = Color(0xFF9B9B9B)
            val ink_3 = Color(0xFF6E6E6E)
        }
    }

    object Semantic {
        val ok = Color(0xFF4E8A5A)
        val ok_bg = Color(0xFFEEF4EF)
        val warn = Color(0xFF8A661C)
        val warn_bg = Color(0xFFF7F1E3)
        val err = Color(0xFFB34A42)
        val err_bg = Color(0xFFF9ECEA)
    }

    object Hues {
        object Stone {
            val accent = Color(0xFF78716C)
            val accentInk = Color(0xFF78716C)
            val accentInkDark = Color(0xFF938D89)
            val ambA = Color(0xE0D5CDC4)
            val ambB = Color(0xB8C6D1D6)
            val ambADark = Color(0x38938D89)
            val ambBDark = Color(0x29767E8C)
        }
        object Sage {
            val accent = Color(0xFF7C9483)
            val accentInk = Color(0xFF65796B)
            val accentInkDark = Color(0xFF7C9483)
            val ambA = Color(0xE6CCDBCE)
            val ambB = Color(0xBDD7DDCB)
            val ambADark = Color(0x3D7C9483)
            val ambBDark = Color(0x296C766C)
        }
        object Steel {
            val accent = Color(0xFF6E88A0)
            val accentInk = Color(0xFF60778C)
            val accentInkDark = Color(0xFF8FA5BC)
            val ambA = Color(0xE6C9D7E4)
            val ambB = Color(0xBDD5D5E2)
            val ambADark = Color(0x426E88A0)
            val ambBDark = Color(0x2E626C82)
        }
        object Mauve {
            val accent = Color(0xFF967F95)
            val accentInk = Color(0xFF846F83)
            val accentInkDark = Color(0xFFA896A7)
            val ambA = Color(0xE6DFD3DF)
            val ambB = Color(0xBDDBD5E4)
            val ambADark = Color(0x3D967F95)
            val ambBDark = Color(0x2980768C)
        }
        object Dusk {
            val accent = Color(0xFFA08A76)
            val accentInk = Color(0xFF867363)
            val accentInkDark = Color(0xFFB29C86)
            val ambA = Color(0xE6E5D7C7)
            val ambB = Color(0xBDE1D1C3)
            val ambADark = Color(0x3DA08A76)
            val ambBDark = Color(0x298A7B67)
        }
    }

    object Radius {
        val L = 20; val M = 16; val S = 12  // dp
    }

    object Motion {
        const val D_INSTANT_MS = 100; const val D_FAST_MS = 180
        const val D_NORMAL_MS = 280; const val D_SLOW_MS = 320
    }
}
