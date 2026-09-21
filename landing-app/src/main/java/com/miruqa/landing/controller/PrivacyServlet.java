package com.miruqa.landing.controller;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;

/** プライバシーポリシー（案）。 */
@WebServlet("/contact/privacy")
public class PrivacyServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (!com.miruqa.landing.config.AppConfig.contactEnabled()) { resp.sendError(HttpServletResponse.SC_NOT_FOUND); return; }
        req.getRequestDispatcher("/WEB-INF/views/privacy.jsp").forward(req, resp);
    }
}
