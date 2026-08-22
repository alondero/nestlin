package com.github.alondero.nestlin.session

/**
 * Snapshot of the signed-in RetroAchievements account (issue #268).
 *
 * Populated from `rc_client_user_t` after a successful password or token
 * login. The strings are owned copies — once [RaAccount] is in the UI's
 * hands, the native side can be torn down without losing the displayed
 * display name or score.
 *
 * The fields mirror rcheevos's `rc_client_user_t`:
 *
 *   - [username] / [displayName] — server-corrected identity. The display
 *     name is what the UI shows in the menu and profile window.
 *   - [score] / [scoreSoftcore] — total points earned across all games
 *     (hardcore is always 0 in Nestlin; the server returns the same value
 *     for hardcore vs softcore users, but we surface softcore explicitly).
 *   - [unreadMessages] — count of unread messages on the web profile.
 *     Surfaced in the profile window per issue #268 AC.
 *   - [avatarUrl] — absolute URL to the user's avatar image. The HTTP
 *     bridge redacts the `t=` query parameter before the URL reaches any
 *     log line or UI element.
 */
data class RaAccount(
    val username: String,
    val displayName: String,
    val score: Int,
    val scoreSoftcore: Int,
    val unreadMessages: Int,
    val avatarUrl: String,
)

/**
 * Sealed hierarchy of RetroAchievements sign-in states (issue #268).
 *
 * The state machine is the only observable sign-in surface — the menu
 * binds to it directly, the HTTP bridge drives transitions via the
 * service's callback hooks, and tests assert against the exact sequence
 * of states a login attempt traverses.
 *
 * ```
 *     Unavailable ────────────────► SignedOut ──login()──► Authenticating
 *                                       ▲                     │
 *                                       │                     ├──► SignedIn(account)
 *                                       │                     │
 *                                       └────── logout() ◄─────┤
 *                                                              │
 *                                                              └──► Offline(cause)
 * ```
 *
 * - [Unavailable]: the native façade library is missing or corrupt. The
 *   menu shows a "RetroAchievements unavailable" message and the login
 *   action is disabled. This is the terminal state for a missing-library
 *   session — no transition out without restarting Nestlin.
 * - [SignedOut]: no user is logged in and no login attempt is in flight.
 *   The login action is enabled. The user can submit credentials.
 * - [Authenticating]: a login attempt is in flight (password or token
 *   restoration). The login action is disabled. A duplicate request is
 *   rejected at the service layer per the issue #268 single-flight rule.
 * - [SignedIn]: a user is logged in. The login action becomes "Sign Out",
 *   and a non-modal profile window can show the [RaAccount] snapshot.
 * - [Offline]: the network or transport is unreachable. The credentials
 *   (if previously saved) are preserved so a reconnect can restore the
 *   session; the menu shows a "retry" affordance.
 */
sealed class RaSignInState {
    object Unavailable : RaSignInState()
    object SignedOut : RaSignInState()
    object Authenticating : RaSignInState()
    data class SignedIn(val account: RaAccount) : RaSignInState()
    data class Offline(val cause: String) : RaSignInState()
}