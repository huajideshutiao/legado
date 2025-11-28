package me.ag2s.epublib.domain;

import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;

/**
 * MediaType is used to tell the type of content a resource is.
 * <p>
 * Examples of mediatypes are image/gif, text/css and application/xhtml+xml
 * <p>
 * All allowed mediaTypes are maintained bye the MediaTypeService.
 *
 * @author paul
 * @see MediaTypes
 */
public record MediaType(String name, String defaultExtension,
                        Collection<String> extensions) implements Serializable {

    @Serial
    private static final long serialVersionUID = -7256091153727506788L;

    public MediaType(String name, String defaultExtension) {
        this(name, defaultExtension, new String[]{defaultExtension});
    }

    public MediaType(String name, String defaultExtension,
                     String[] extensions) {
        this(name, defaultExtension, Arrays.asList(extensions));
    }

    public int hashCode() {
        if (name == null) {
            return 0;
        }
        return name.hashCode();
    }

    public boolean equals(Object otherMediaType) {
        if (!(otherMediaType instanceof MediaType)) {
            return false;
        }
        return name.equals(((MediaType) otherMediaType).name());
    }

    @SuppressWarnings("NullableProblems")
    public String toString() {
        return name;
    }
}
