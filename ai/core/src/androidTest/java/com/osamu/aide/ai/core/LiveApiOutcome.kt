package com.osamu.aide.ai.core

/**
 * What a live provider request actually told us, when it failed.
 *
 * **A provider validates the model before it checks the quota.** Both of them
 * do, and it was measured rather than assumed: with a key whose account has no
 * credit, a real model comes back
 *
 * ```
 * gpt-5.6                     429 credit_balance_exhausted
 * gemini-3.8-flash            429 RESOURCE_EXHAUSTED
 * ```
 *
 * while a model that does not exist comes back
 *
 * ```
 * definitely-not-a-model-xyz  404 model_not_found / NOT_FOUND
 * gemini-2.5-pro              404 "no longer available to new users"
 * ```
 *
 * That distinction is worth more than it looks. It means **the one question the
 * picker most needs answered -- are these model ids real -- can be answered
 * without paying for a single token**, and it is the question that has already
 * bitten this project twice (`ai/core/FINDINGS.md` §§14-15). The stronger claim,
 * that a model *answers*, still needs credit.
 *
 * Both clients format a failure as `"<Provider> request failed (<code>): <body>"`,
 * which is what this reads.
 */
internal sealed interface LiveApiOutcome {

    /** The request succeeded. The strongest possible answer. */
    data object Answered : LiveApiOutcome

    /** The model is real; the account cannot pay for it. */
    data object Unpaid : LiveApiOutcome

    /** The provider does not know this model. A dead entry in the picker. */
    data class Unknown(val detail: String) : LiveApiOutcome

    /**
     * Neither -- a throttle, a transport error, an empty body.
     *
     * **An empty 404 is not a missing model.** Sending several model probes
     * back to back earned a burst of `HTTP 404` with a *zero-length body*,
     * including for `gemini-3.8-flash`, which had answered `429` seconds
     * earlier and did so again seconds later. A real model-not-found always
     * names the model in its body. Treating that empty 404 as a dead model
     * would fail the suite over Google's rate limiter.
     */
    data class Inconclusive(val detail: String) : LiveApiOutcome

    companion object {
        private val FAILURE = Regex("""request failed \((\d+)\): (.*)""", RegexOption.DOT_MATCHES_ALL)

        fun of(failure: Throwable): LiveApiOutcome {
            val message = failure.message.orEmpty()
            val match = FAILURE.find(message) ?: return Inconclusive(message)
            val code = match.groupValues[1]
            val body = match.groupValues[2].trim()
            return when {
                code == "429" -> Unpaid
                code == "404" && body.isEmpty() -> Inconclusive("empty 404, probably throttled")
                code == "404" -> Unknown(body)
                else -> Inconclusive(message)
            }
        }
    }
}
