package emoji;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EmojiServiceTest {

    private static EmojiService service;

    @BeforeAll
    static void setUp() {
        service = new EmojiService(new EmojiRepository());
    }

    @Test
    void loadsOfflineDataset() {
        assertFalse(service.repository().all().isEmpty());
        assertTrue(service.repository().all().size() > 1000, "expected full dataset");
    }

    @Test
    void searchesByKeyword() {
        assertTrue(containsCharacter(service.search("laugh"), "\uD83E\uDD23")); // 🤣
        assertTrue(containsCharacter(service.search("laugh"), "\uD83D\uDE02")); // 😂
        assertTrue(containsCharacter(service.search("fire"), "\uD83D\uDD25"));  // 🔥
        assertTrue(containsCharacter(service.search("rocket"), "\uD83D\uDE80")); // 🚀
    }

    @Test
    void searchesByExactName() {
        List<Emoji> results = service.search("grinning face");
        assertFalse(results.isEmpty());
        // exact name match ranks first
        assertTrue(results.get(0).name().equalsIgnoreCase("grinning face"));
    }

    @Test
    void heartSearchReturnsMultiple() {
        assertTrue(service.search("heart").size() >= 3);
        assertTrue(containsCharacter(service.search("heart"), "\u2764\uFE0F"));
    }

    @Test
    void checkSearchFindsCheckMark() {
        assertTrue(containsCharacter(service.search("check"), "\u2705")); // ✅
    }

    @Test
    void emptySearchReturnsNothing() {
        assertTrue(service.search("").isEmpty());
        assertTrue(service.search(null).isEmpty());
    }

    @Test
    void searchIsCaseInsensitive() {
        assertFalse(service.search("GRINNING").isEmpty());
        assertFalse(service.search("SmIlEy").isEmpty());
    }

    @Test
    void categoriesArePopulated() {
        for (EmojiCategory category : EmojiCategory.values()) {
            if (category == EmojiCategory.RECENTLY_USED) {
                continue;
            }
            assertFalse(service.byCategory(category).isEmpty(), category + " should have emojis");
        }
    }

    @Test
    void everyEmojiHasCategoryAndName() {
        for (Emoji e : service.repository().all()) {
            assertFalse(e.name().isBlank());
            assertFalse(e.character().isBlank());
        }
    }

    private static boolean containsCharacter(List<Emoji> list, String ch) {
        return list.stream().anyMatch(e -> e.character().equals(ch));
    }
}
