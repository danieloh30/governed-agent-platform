package com.example.router;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import java.util.Arrays;

@QuarkusMain
public class LabMain implements QuarkusApplication {
    @Inject
    LabLauncher launcher;

    @Inject
    RoutingChecks checks;

    @Override
    public int run(String... args) {
        try {
            switch (args.length == 0 ? "serve" : args[0]) {
                case "serve" -> Quarkus.waitForExit();
                case "run" -> launcher.run(Arrays.copyOfRange(args, 1, args.length));
                case "smoke" -> checks.run();
                default -> throw new IllegalArgumentException("Use serve, run, or smoke");
            }
            return 0;
        } catch (Exception error) {
            System.err.println("FAIL: " + error.getMessage());
            return 1;
        }
    }
}
