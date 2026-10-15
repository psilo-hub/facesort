package free.svoss.facesort.ui;

import free.svoss.facesort.service.FeedbackService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the ownership of the feedback service.
 *
 * <p>The Feedback tab is rebuilt on every language change. A view that creates
 * its own {@link FeedbackService} would create a second {@link java.net.http.HttpClient},
 * and an {@code HttpClient} cannot be closed before Java 21 — its selector
 * thread would live until the process ends. The service therefore has to be
 * handed in.</p>
 *
 * <p>The view itself cannot be constructed here (JavaFX controls need an
 * initialized toolkit), so the constructor signature is checked instead.</p>
 */
class FeedbackViewTest {

    @Test
    void everyConstructorTakesTheSharedService() {
        Constructor<?>[] constructors = FeedbackView.class.getDeclaredConstructors();

        assertTrue(constructors.length > 0, "FeedbackView must have a constructor");
        for (Constructor<?> constructor : constructors) {
            assertTrue(hasParameter(constructor, FeedbackService.class),
                    "every FeedbackView constructor must receive the shared FeedbackService, otherwise "
                            + "the view creates its own HttpClient on every language change and leaks it: "
                            + constructor);
        }
    }

    private static boolean hasParameter(Constructor<?> constructor, Class<?> type) {
        for (Class<?> parameter : constructor.getParameterTypes()) {
            if (parameter == type) {
                return true;
            }
        }
        return false;
    }
}
