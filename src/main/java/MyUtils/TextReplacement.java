package MyUtils;

import com.intellij.openapi.util.TextRange;

import java.util.Objects;

public class TextReplacement {
    private final TextRange range;
    private final String replacementText;

    public TextReplacement(TextRange range, String replacementText) {
        this.range = range;
        this.replacementText = replacementText;
    }

    public TextRange getRange() {
        return range;
    }

    public String getReplacementText() {
        return replacementText;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TextReplacement that = (TextReplacement) o;
        return Objects.equals(range, that.range) &&
                Objects.equals(replacementText, that.replacementText);
    }

    @Override
    public int hashCode() {
        return Objects.hash(range, replacementText);
    }
}
