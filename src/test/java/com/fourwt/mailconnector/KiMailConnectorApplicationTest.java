package com.fourwt.mailconnector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Sehr kleiner Smoke-Test für die Startklasse.
 *
 * <p>Die eigentliche {@code main()}-Methode wird hier bewusst nicht gestartet:
 * Sie benötigt reale Umgebungsvariablen und blockiert danach dauerhaft.</p>
 */
class KiMailConnectorApplicationTest {

    @Test
    void applicationClassExists() {
        assertNotNull(KiMailConnectorApplication.class);
    }
}
