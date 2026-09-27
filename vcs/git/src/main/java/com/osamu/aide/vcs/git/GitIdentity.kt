package com.osamu.aide.vcs.git

import android.content.Context

/**
 * Who a commit says it is from.
 *
 * On a desktop this comes from `~/.gitconfig` and nobody thinks about it. On a
 * device there is no such file: spike R6 found `FS.detect()` reports a null
 * `userHome` and a null `gitSystemConfig`, so JGit has nothing to read and its
 * own fallback identity is assembled from system properties Android fills in
 * with values like `root@localhost`. A commit made that way is not wrong so
 * much as unattributable, and rewriting history to fix authorship is the least
 * forgiving operation git has.
 *
 * So identity is asked for before the first commit rather than defaulted, and
 * [GitIdentityStore.read] returning null is the signal to ask.
 */
data class GitIdentity(val name: String, val email: String) {

    /**
     * Rejects the shapes that produce a commit nobody can attribute.
     *
     * Deliberately not an email validator. `git` itself accepts anything
     * without whitespace, hosting providers differ on what they will match
     * against an account, and a regex here would reject addresses that work
     * while still admitting ones that do not. The check is for the mistakes
     * that are unambiguous: nothing typed, and something that cannot be an
     * address at all.
     */
    /**
     * The first thing wrong with this identity, or null.
     *
     * Composed from the per-field checks so a caller wanting one message and a
     * caller wanting to mark one field cannot disagree about what is wrong.
     */
    fun validate(): String? = nameProblem() ?: emailProblem()

    /**
     * What is wrong with the name specifically.
     *
     * Separate from [emailProblem] so a field is only marked when it is the
     * field at fault. A single combined check made the name turn red because
     * the *email* was empty, which tells the user to fix the wrong thing.
     */
    fun nameProblem(): String? {
        val name = trimmed().name
        return when {
            name.isEmpty() -> "A name is needed -- it is what appears on every commit."
            // git's commit object format uses '<' and '>' to delimit the
            // address, so these cannot survive a round trip.
            '<' in name || '>' in name ->
                "Angle brackets are not allowed: git uses them to delimit the address."
            else -> null
        }
    }

    /**
     * What is wrong with the email specifically.
     *
     * Deliberately not an email validator. `git` accepts anything without
     * whitespace, hosting providers differ on what they will match to an
     * account, and a stricter rule would reject addresses that work while
     * still admitting ones that do not. Only the unambiguous mistakes.
     *
     * Checks the **trimmed** form, because that is what
     * [GitIdentityStore.save] stores: validating the raw text would refuse a
     * padded address the store would have accepted perfectly well.
     */
    fun emailProblem(): String? {
        val email = trimmed().email
        return when {
            email.isEmpty() ->
                "An email is needed. Hosting providers match commits to accounts by it."
            '@' !in email -> "That does not look like an email address."
            email.any { it.isWhitespace() } -> "An email address cannot contain spaces."
            '<' in email || '>' in email ->
                "Angle brackets are not allowed: git uses them to delimit the address."
            else -> null
        }
    }

    /** The form that is stored and written into a commit. */
    fun trimmed(): GitIdentity = GitIdentity(name.trim(), email.trim())
}

/**
 * Stores the committing identity.
 *
 * In the clear, and that is deliberate: a name and an email address are printed
 * into every commit object this app creates and pushed to a public host. They
 * are the opposite of a secret, and encrypting them would imply otherwise.
 * [GitCredentialStore] is where the thing that *is* secret lives.
 */
class GitIdentityStore(context: Context) {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Null when the user has not set one, which is the signal to ask. */
    fun read(): GitIdentity? {
        val name = preferences.getString(KEY_NAME, null) ?: return null
        val email = preferences.getString(KEY_EMAIL, null) ?: return null
        return GitIdentity(name, email)
    }

    /**
     * Stores [identity], or does nothing if it would not make a valid commit.
     *
     * Silent rather than throwing, matching `ApiKeyStore.saveBaseUrl`: the UI
     * validates before offering to save, so reaching here with something
     * invalid is a bug in the caller, and crashing a settings screen over it
     * helps nobody. Returns what was wrong so a caller that did not check can.
     */
    fun save(identity: GitIdentity): String? {
        val problem = identity.validate()
        if (problem != null) return problem

        // commit(), not apply(), for the same reason ApiKeyStore does: this is
        // typed once by hand, and losing it to a process death means being
        // asked again with no explanation.
        val stored = identity.trimmed()
        preferences.edit()
            .putString(KEY_NAME, stored.name)
            .putString(KEY_EMAIL, stored.email)
            .commit()
        return null
    }

    fun clear() {
        preferences.edit().remove(KEY_NAME).remove(KEY_EMAIL).commit()
    }

    private companion object {
        const val FILE = "aide-git"
        const val KEY_NAME = "identity.name"
        const val KEY_EMAIL = "identity.email"
    }
}
