package com.bobbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service to manage paginated message sessions for AI responses.
 */
public class PaginationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PaginationService.class);
    private final Map<String, PagedSession> sessions = new ConcurrentHashMap<>();

    public record PagedSession(String title, String naturalResponse, List<String> pages, int currentPage) {}

    /**
     * Create a new paginated session.
     *
     * @param title the title of the report
     * @param naturalResponse the AI's natural chat response
     * @param items the list of items to paginate
     * @param itemsPerPage number of items per page
     * @return unique session ID
     */
    public String createSession(String title, String naturalResponse, List<String> items, int itemsPerPage) {
        List<String> pages = new ArrayList<>();
        for (int i = 0; i < items.size(); i += itemsPerPage) {
            List<String> chunk = items.subList(i, Math.min(i + itemsPerPage, items.size()));
            StringBuilder sb = new StringBuilder();
            for (String item : chunk) {
                sb.append(item).append("\n");
            }
            pages.add(sb.toString());
        }

        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new PagedSession(title, naturalResponse, pages, 0));
        LOGGER.debug("Created pagination session {} with {} pages", sessionId, pages.size());
        return sessionId;
    }

    public PagedSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public void updateSessionPage(String sessionId, int newPage) {
        PagedSession session = sessions.get(sessionId);
        if (session != null) {
            sessions.put(sessionId, new PagedSession(session.title(), session.naturalResponse(), session.pages(), newPage));
        }
    }

    public void updateSessionResponse(String sessionId, String naturalResponse) {
        PagedSession session = sessions.get(sessionId);
        if (session != null) {
            sessions.put(sessionId, new PagedSession(session.title(), naturalResponse, session.pages(), session.currentPage()));
        }
    }

    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
    }
}
