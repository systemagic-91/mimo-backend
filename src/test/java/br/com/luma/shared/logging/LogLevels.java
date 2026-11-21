package br.com.luma.shared.logging;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

/**
 * Liga um logger num nível para um teste e devolve como desfazer. Os testes de contexto aplicam os níveis do
 * application.yml (econômicos) no logback da JVM inteira, então os testes de log não podem depender da ordem.
 */
public final class LogLevels {

    private LogLevels() {
    }

    public static AutoCloseable set(String loggerName, Level level) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
        Level previous = logger.getLevel();
        logger.setLevel(level);
        return () -> logger.setLevel(previous);
    }
}
