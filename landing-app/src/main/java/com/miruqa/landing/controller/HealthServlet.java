package com.miruqa.landing.controller;

import com.miruqa.landing.config.AppContextListener;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;

/** ALBのヘルスチェック用（GET /health）。DBに接続できなければ 503。 */
@WebServlet("/health")
public class HealthServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        try {
            var repo = AppContextListener.services(getServletContext()).inquiries();
            if (repo != null) repo.ping();   // フォーム停止中は、DBを使わない
            resp.getWriter().write("{\"status\":\"UP\"}");
        } catch (RuntimeException e) {
            resp.setStatus(503);
            resp.getWriter().write("{\"status\":\"DOWN\"}");
        }
    }
}
