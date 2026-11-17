package server;

import com.sun.net.httpserver.HttpExchange;

/**
 * AdminAuth - answers ONE question: "is this request from a logged-in administrator?"
 *
 * PHASE 7: administrator login does not exist yet, so the answer is always NO.
 *   That is the safe default. It means restricted pathways are never permitted for anybody,
 *   and nothing a client puts in its request can change that.
 * PHASE 10: this method will check the "Authorization: Bearer <token>" header against the
 *   server's table of tokens that were handed out by POST /api/admin/login.
 */
final class AdminAuth {
    private AdminAuth() { }

    static boolean isAdmin(HttpExchange exchange) {
        return false;
    }
}
