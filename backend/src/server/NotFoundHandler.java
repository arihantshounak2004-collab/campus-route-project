package server;

import com.sun.net.httpserver.HttpExchange;

/** Answers every /api/... address that no other handler owns with a JSON 404. */
class NotFoundHandler extends BaseHandler {
    NotFoundHandler(String corsOrigin) { super(corsOrigin); }

    @Override
    protected void route(HttpExchange exchange) {
        throw new ApiException(404, "No such API endpoint");
    }
}
