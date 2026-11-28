package dev.agwidgets.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.util.Locale

/** Fixed fallback clock (epoch seconds) used by every balance test. */
private const val FIXED_NOW_SEC = 1_766_000_000.0

/** Epoch seconds for 2026-11-25T00:00:00Z. */
private const val NOW_2026_11_25_SEC = 1_795_564_800.0

/** One second after 2026-12-01T00:00:00Z. */
private const val AFTER_2026_12_01_SEC = 1_796_083_201.0

/**
 * Pure JVM tests for the ZCode response parsers and the OAuth URL allowlist.
 *
 * No Android APIs, no network: only the top-level parsing functions from
 * `ZCodeClient.kt`. Clocks are always injected via `fallbackNowSec`.
 */
class ZCodeClientTest {

    private fun json(raw: String): JSONObject = JSONObject(raw.trimIndent())

    // ---- parseTouchResponse ----

    @Test
    fun touchBannerClaimButtonIsVisible() {
        val body = json(
            """
            {
              "code": 0,
              "data": {
                "deliveries": [
                  {
                    "resource_position": "banner",
                    "banner": {
                      "title": {"content": "Бонус"},
                      "buttons": [
                        {
                          "text": {"content": "Забрать 500M"},
                          "action": {
                            "type": "claim_zcode_plan",
                            "args": {"plan_id": "plan-42"}
                          }
                        }
                      ]
                    }
                  }
                ]
              }
            }
            """
        )

        val result = parseTouchResponse(body, 200)

        assertEquals(ZCodeStatus.COUPON_VISIBLE, result.couponState)
        assertEquals("plan-42", result.planId)
        assertEquals("Забрать 500M", result.matched)
        assertNull(result.errorKind)
    }

    @Test
    fun touchPopupClaimButtonIsVisible() {
        val body = json(
            """
            {
              "code": 0,
              "data": {
                "deliveries": [
                  {
                    "resource_position": "popup",
                    "popup": {
                      "title": {"content": "Подарок"},
                      "buttons": [
                        {
                          "text": {"content": "Активировать"},
                          "action": {
                            "type": "claim_zcode_plan",
                            "args": {"plan_id": "plan-77"}
                          }
                        }
                      ]
                    }
                  }
                ]
              }
            }
            """
        )

        val result = parseTouchResponse(body, 200)

        assertEquals(ZCodeStatus.COUPON_VISIBLE, result.couponState)
        assertEquals("plan-77", result.planId)
        assertEquals("Активировать", result.matched)
        assertNull(result.errorKind)
    }

    @Test
    fun touchClaimInBannerSuccessPopupIsVisible() {
        val body = json(
            """
            {
              "code": 0,
              "data": {
                "deliveries": [
                  {
                    "resource_position": "banner",
                    "campaign_id": "weekend-build",
                    "banner": {
                      "title": {"content": "ZCODE WEEKEND BUILD"},
                      "buttons": [
                        {"text": {"content": "Later"}, "action": {"type": "close"}}
                      ],
                      "success_popup": {
                        "title": {"content": "Success"},
                        "buttons": [
                          {
                            "text": {"content": "Claim 300M"},
                            "action": {
                              "type": "claim_zcode_plan",
                              "args": {"plan_id": "plan-nested"}
                            }
                          }
                        ]
                      }
                    }
                  }
                ]
              }
            }
            """
        )

        val result = parseTouchResponse(body, 200)

        assertEquals(ZCodeStatus.COUPON_VISIBLE, result.couponState)
        assertEquals("plan-nested", result.planId)
        assertEquals("Claim 300M", result.matched)
        assertNull(result.errorKind)
    }

    @Test
    fun clientLanguageNormalizesNonChineseToEnglish() {
        assertEquals("zh-CN", zcodeClientLanguage(Locale.forLanguageTag("zh-CN")))
        assertEquals("zh-CN", zcodeClientLanguage(Locale.forLanguageTag("zh")))
        assertEquals("en-US", zcodeClientLanguage(Locale.forLanguageTag("ru-RU")))
        assertEquals("en-US", zcodeClientLanguage(Locale.forLanguageTag("uk-UA")))
        assertEquals("en-US", zcodeClientLanguage(Locale.forLanguageTag("de-DE")))
    }

    @Test
    fun touchEmptyDeliveriesIsAbsent() {
        val body = json("""{"code": 0, "data": {"deliveries": []}}""")

        val result = parseTouchResponse(body, 200)

        assertEquals(ZCodeStatus.COUPON_ABSENT, result.couponState)
        assertNull(result.errorKind)
    }

    @Test
    fun touchMissingDeliveriesIsUnknown() {
        val body = json("""{"code": 0, "data": {}}""")

        val result = parseTouchResponse(body, 200)

        assertEquals(ZCodeStatus.COUPON_UNKNOWN, result.couponState)
        assertNotNull(result.errorKind)
    }

    @Test
    fun touchNonZeroCodeIsUnknownApiChanged() {
        val body = json("""{"code": 1001, "data": {"deliveries": []}}""")

        val result = parseTouchResponse(body, 200)

        assertEquals(ZCodeStatus.COUPON_UNKNOWN, result.couponState)
        assertEquals("api_changed", result.errorKind)
    }

    @Test
    fun touchRateLimitedIsUnknown() {
        val body = json("""{"code": 0, "data": {"deliveries": []}}""")

        val result = parseTouchResponse(body, 429)

        assertEquals(ZCodeStatus.COUPON_UNKNOWN, result.couponState)
        assertEquals("rate_limited", result.errorKind)
    }

    @Test
    fun touchServerErrorIsUnknown() {
        val body = json("""{"code": 0, "data": {"deliveries": []}}""")

        val result = parseTouchResponse(body, 500)

        assertEquals(ZCodeStatus.COUPON_UNKNOWN, result.couponState)
        assertEquals("server_error", result.errorKind)
    }

    @Test
    fun touchUnauthorizedIsUnknownAuthExpired() {
        val body = json("""{"code": 0, "data": {"deliveries": []}}""")

        val result = parseTouchResponse(body, 401)

        assertEquals(ZCodeStatus.COUPON_UNKNOWN, result.couponState)
        assertEquals("auth_expired", result.errorKind)
    }

    @Test
    fun touchForbiddenAndBusiness401AreAuthExpired() {
        val body = json("""{"code": 0, "data": {"deliveries": []}}""")
        assertEquals("auth_expired", parseTouchResponse(body, 403).errorKind)

        val biz401 = json("""{"code": 401, "data": {}}""")
        val res = parseTouchResponse(biz401, 200)
        assertEquals(ZCodeStatus.COUPON_UNKNOWN, res.couponState)
        assertEquals("auth_expired", res.errorKind)
    }

    @Test
    fun touchNullBodyIsUnknownWithoutThrowing() {
        val result = parseTouchResponse(null, 200)

        assertEquals(ZCodeStatus.COUPON_UNKNOWN, result.couponState)
        assertNotNull(result.errorKind)
    }

    @Test
    fun touchNonObjectDeliveriesAreAbsent() {
        val body = json("""{"code": 0, "data": {"deliveries": ["junk", 42, null]}}""")

        val result = parseTouchResponse(body, 200)

        assertEquals(ZCodeStatus.COUPON_ABSENT, result.couponState)
        assertNull(result.errorKind)
    }

    // ---- parseBalanceResponse ----

    @Test
    fun balanceAllExpiredPlansAreNotSummed() {
        val body = json(
            """
            {
              "code": 0,
              "data": {
                "plans": [
                  {
                    "plan_id": "p1",
                    "user_plan_id": "u1",
                    "status": "expired",
                    "ends_at": 1700000000
                  }
                ],
                "balances": [
                  {
                    "plan_id": "p1",
                    "user_plan_id": "u1",
                    "total_units": 500000000,
                    "available_units": 300000000
                  }
                ]
              }
            }
            """
        )

        val result = parseBalanceResponse(body, 200, FIXED_NOW_SEC)

        assertNull(result.remainingUnits)
        assertNull(result.totalUnits)
        assertNull(result.errorKind)
    }

    @Test
    fun balanceActivePlanIsSummed() {
        val body = json(
            """
            {
              "code": 0,
              "data": {
                "plans": [
                  {
                    "plan_id": "p1",
                    "user_plan_id": "u1",
                    "status": "active",
                    "ends_at": 1766086400
                  }
                ],
                "balances": [
                  {
                    "plan_id": "p1",
                    "user_plan_id": "u1",
                    "total_units": 500000000,
                    "available_units": 300000000
                  }
                ]
              }
            }
            """
        )

        val result = parseBalanceResponse(body, 200, FIXED_NOW_SEC)

        assertEquals(300_000_000L, result.remainingUnits)
        assertEquals(500_000_000L, result.totalUnits)
        assertNull(result.errorKind)
    }

    @Test
    fun balanceOnlyActivePlanContributesWhenPlansMixed() {
        val body = json(
            """
            {
              "code": 0,
              "data": {
                "plans": [
                  {
                    "plan_id": "p1",
                    "user_plan_id": "u1",
                    "status": "expired",
                    "ends_at": 1700000000
                  },
                  {
                    "plan_id": "p2",
                    "user_plan_id": "u2",
                    "status": "active",
                    "ends_at": 1766086400
                  }
                ],
                "balances": [
                  {
                    "plan_id": "p1",
                    "user_plan_id": "u1",
                    "total_units": 200000000,
                    "available_units": 100000000
                  },
                  {
                    "plan_id": "p2",
                    "user_plan_id": "u2",
                    "total_units": 500,
                    "available_units": 300
                  }
                ]
              }
            }
            """
        )

        val result = parseBalanceResponse(body, 200, FIXED_NOW_SEC)

        assertEquals(300L, result.remainingUnits)
        assertEquals(500L, result.totalUnits)
    }

    @Test
    fun balanceEmptyPlansSumAllBalances() {
        val body = json(
            """
            {
              "code": 0,
              "data": {
                "plans": [],
                "balances": [
                  {
                    "plan_id": "x",
                    "user_plan_id": "ux",
                    "total_units": 100,
                    "available_units": 50
                  },
                  {
                    "plan_id": "y",
                    "user_plan_id": "uy",
                    "total_units": 200,
                    "available_units": 60
                  }
                ]
              }
            }
            """
        )

        val result = parseBalanceResponse(body, 200, FIXED_NOW_SEC)

        assertEquals(110L, result.remainingUnits)
        assertEquals(300L, result.totalUnits)
    }

    @Test
    fun balanceServerTimeInMillisecondsIsNormalized() {
        val body = json(
            """
            {
              "code": 0,
              "data": {
                "server_time": 1766000010000,
                "plans": [
                  {
                    "plan_id": "p1",
                    "user_plan_id": "u1",
                    "status": "active",
                    "ends_at": 1766000100
                  }
                ],
                "balances": [
                  {
                    "plan_id": "p1",
                    "user_plan_id": "u1",
                    "total_units": 500000000,
                    "available_units": 300000000
                  }
                ]
              }
            }
            """
        )

        val result = parseBalanceResponse(body, 200, FIXED_NOW_SEC)

        // Treating 1766000010000 as seconds would mark the plan expired.
        assertEquals(300_000_000L, result.remainingUnits)
        assertEquals(500_000_000L, result.totalUnits)
        assertNull(result.errorKind)
    }

    @Test
    fun balanceIsoEndsAtIsParsedAndExpiresAfterwards() {
        val body = json(
            """
            {
              "code": 0,
              "data": {
                "plans": [
                  {
                    "plan_id": "p1",
                    "user_plan_id": "u1",
                    "status": "active",
                    "ends_at": "2026-12-01T00:00:00Z"
                  }
                ],
                "balances": [
                  {
                    "plan_id": "p1",
                    "user_plan_id": "u1",
                    "total_units": 100,
                    "available_units": 40
                  }
                ]
              }
            }
            """
        )

        val active = parseBalanceResponse(body, 200, NOW_2026_11_25_SEC)
        assertEquals(40L, active.remainingUnits)
        assertEquals(100L, active.totalUnits)
        assertEquals("2026-12-01T00:00:00Z", active.endsAt)

        val expired = parseBalanceResponse(body, 200, AFTER_2026_12_01_SEC)
        assertNull(expired.remainingUnits)
        assertNull(expired.totalUnits)
    }

    @Test
    fun balanceFractionalUnitsAreRounded() {
        val body = json(
            """
            {
              "code": 0,
              "data": {
                "plans": [],
                "balances": [
                  {
                    "plan_id": "p1",
                    "total_units": 2000.4,
                    "available_units": 1234.6
                  }
                ]
              }
            }
            """
        )

        val result = parseBalanceResponse(body, 200, FIXED_NOW_SEC)

        assertEquals(1235L, result.remainingUnits)
        assertEquals(2000L, result.totalUnits)
    }

    @Test
    fun balanceEmptyBalancesYieldNullTotal() {
        val body = json("""{"code": 0, "data": {"plans": [], "balances": []}}""")

        val result = parseBalanceResponse(body, 200, FIXED_NOW_SEC)

        assertNull(result.remainingUnits)
        assertNull(result.totalUnits)
        assertNull(result.errorKind)
    }

    @Test
    fun balanceUnauthorizedMarksAuthExpired() {
        val body = json("""{"code": 0, "data": {}}""")

        val result = parseBalanceResponse(body, 401, FIXED_NOW_SEC)

        assertEquals("auth_expired", result.errorKind)
        assertNull(result.remainingUnits)
        assertNull(result.totalUnits)
    }

    @Test
    fun balanceForbiddenAndBusiness401MarkAuthExpired() {
        val body = json("""{"code": 0, "data": {}}""")
        assertEquals("auth_expired", parseBalanceResponse(body, 403, FIXED_NOW_SEC).errorKind)

        val biz401 = json("""{"code": 401, "data": {}}""")
        assertEquals("auth_expired", parseBalanceResponse(biz401, 200, FIXED_NOW_SEC).errorKind)
    }

    @Test
    fun balanceRateLimitedMarksRateLimited() {
        val body = json("""{"code": 0, "data": {}}""")

        val result = parseBalanceResponse(body, 429, FIXED_NOW_SEC)

        assertEquals("rate_limited", result.errorKind)
        assertNull(result.remainingUnits)
        assertNull(result.totalUnits)
    }

    @Test
    fun balanceServerErrorMarksServerError() {
        val body = json("""{"code": 0, "data": {}}""")

        val result = parseBalanceResponse(body, 500, FIXED_NOW_SEC)

        assertEquals("server_error", result.errorKind)
        assertNull(result.remainingUnits)
        assertNull(result.totalUnits)
    }

    @Test
    fun balanceNullBodyReportsParseError() {
        val result = parseBalanceResponse(null, 200, FIXED_NOW_SEC)

        assertNotNull(result.errorKind)
        assertNull(result.remainingUnits)
        assertNull(result.totalUnits)
    }

    @Test
    fun balanceNonZeroCodeWithoutSuccessIsApiChanged() {
        val body = json("""{"code": 1001, "data": {"balances": []}}""")

        val result = parseBalanceResponse(body, 200, FIXED_NOW_SEC)

        assertEquals("api_changed", result.errorKind)
        assertNull(result.remainingUnits)
        assertNull(result.totalUnits)
    }

    @Test
    fun balanceFallsBackToTotalMinusUsed() {
        val body = json(
            """
            {
              "code": 0,
              "data": {
                "plans": [],
                "balances": [
                  {
                    "plan_id": "p1",
                    "total_units": 1000,
                    "used_units": 300
                  }
                ]
              }
            }
            """
        )

        val result = parseBalanceResponse(body, 200, FIXED_NOW_SEC)

        assertEquals(700L, result.remainingUnits)
        assertEquals(1000L, result.totalUnits)
    }

    @Test
    fun balanceRemainingIsClampedAtZero() {
        val body = json(
            """
            {
              "code": 0,
              "data": {
                "plans": [],
                "balances": [
                  {
                    "plan_id": "p1",
                    "total_units": 100,
                    "used_units": 300
                  }
                ]
              }
            }
            """
        )

        val result = parseBalanceResponse(body, 200, FIXED_NOW_SEC)

        assertEquals(0L, result.remainingUnits)
        assertEquals(100L, result.totalUnits)
    }

    // ---- isTrustedAuthorizeUrl ----

    @Test
    fun acceptsTrustedHttpsAuthorizeUrls() {
        val accepted = listOf(
            "https://z.ai/oauth?x=1",
            "https://chat.z.ai/cli",
            "https://z.ai:443/x",
            "  https://z.ai/oauth  ",
        )

        for (raw in accepted) {
            val trusted = isTrustedAuthorizeUrl(raw)
            assertNotNull("expected trusted: $raw", trusted)
            val normalized = trusted!!
            assertTrue("expected https prefix: $normalized", normalized.startsWith("https://"))
            assertNull("expected no userInfo: $normalized", URI(normalized).userInfo)
        }
    }

    @Test
    fun rejectsUntrustedAuthorizeUrls() {
        val rejected = listOf(
            "http://z.ai/x",
            "https://evil.com/z.ai",
            "https://z.ai.evil.com/x",
            "https://user:pass@z.ai/x",
            "https://z.ai:8443/x",
            "javascript:alert(1)",
            "",
            "not a url",
        )

        for (raw in rejected) {
            assertNull("expected rejected: $raw", isTrustedAuthorizeUrl(raw))
        }
    }
}
