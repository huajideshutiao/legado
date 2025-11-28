package me.ag2s.epublib.browsersupport;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import me.ag2s.epublib.domain.EpubBook;
import me.ag2s.epublib.domain.Resource;

/**
 * A helper class for epub browser applications.
 * <p>
 * It helps moving from one resource to the other, from one resource
 * to the other and keeping other elements of the application up-to-date
 * by calling the NavigationEventListeners.
 *
 * @author paul
 */
public class Navigator implements Serializable {

    @Serial
    private static final long serialVersionUID = 1076126986424925474L;
    private final EpubBook book;
    private int currentSpinePos;
    private Resource currentResource;
    private int currentPagePos;
    private String currentFragmentId;

    private final List<NavigationEventListener> eventListeners = new ArrayList<>();

    public Navigator(EpubBook book) {
        this.book = book;
        this.currentSpinePos = 0;
        if (book != null) {
            this.currentResource = book.getCoverPage();
        }
        this.currentPagePos = 0;
    }

    private synchronized void handleEventListeners(
            NavigationEvent navigationEvent) {
        for (int i = 0; i < eventListeners.size(); i++) {
            NavigationEventListener navigationEventListener = eventListeners.get(i);
            navigationEventListener.navigationPerformed(navigationEvent);
        }
    }

    public void addNavigationEventListener(
            NavigationEventListener navigationEventListener) {
        this.eventListeners.add(navigationEventListener);
    }

    public void gotoResource(String resourceHref, Object source) {
        Resource resource = book.getResources().getByHref(resourceHref);
        gotoResource(resource, source);
    }


    public void gotoResource(Resource resource, Object source) {
        gotoResource(resource, 0, null, source);
    }

    public void gotoResource(Resource resource, int pagePos, String fragmentId,
                             Object source) {
        if (resource == null) {
            return;
        }
        NavigationEvent navigationEvent = new NavigationEvent(source, this);
        this.currentResource = resource;
        this.currentSpinePos = book.getSpine().getResourceIndex(currentResource);
        this.currentPagePos = pagePos;
        this.currentFragmentId = fragmentId;
        handleEventListeners(navigationEvent);

    }

    /**
     * The current position within the spine.
     *
     * @return something &lt; 0 if the current position is not within the spine.
     */
    public int getCurrentSpinePos() {
        return currentSpinePos;
    }

    public Resource getCurrentResource() {
        return currentResource;
    }

    public EpubBook getBook() {
        return book;
    }

    public String getCurrentFragmentId() {
        return currentFragmentId;
    }

    public int getCurrentSectionPos() {
        return currentPagePos;
    }
}
