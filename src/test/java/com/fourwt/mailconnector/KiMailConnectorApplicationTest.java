package com.fourwt.mailconnector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class KiMailConnectorApplicationTest {

    @Test
    void applicationStarts() {
        assertDoesNotThrow(() -> KiMailConnectorApplication.main(new String[0]));
    }
}
