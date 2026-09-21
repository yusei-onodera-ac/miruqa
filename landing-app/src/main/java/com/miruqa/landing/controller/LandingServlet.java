package com.miruqa.landing.controller;

import com.miruqa.landing.config.AppContextListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;

/** トップページ（GET /）。 */
@WebServlet(urlPatterns = {""})
public class LandingServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        var s = AppContextListener.services(getServletContext());
        req.setAttribute("page", s.landing().page());
        req.setAttribute("csrf", s.csrf().issue());
        req.getRequestDispatcher("/WEB-INF/views/index.jsp").forward(req, resp);
    }
}
