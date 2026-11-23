package com.cryptotrading.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Base class that checks {@link ArchitectureRules#all()} against a module's production classes.
 * Put one subclass in the module's root package, e.g. for the order service:
 *
 * <pre>{@code
 * package com.cryptotrading.order;
 *
 * class ArchitectureTest extends AbstractArchitectureTest {}
 * }</pre>
 *
 * The analysed package is the subclass's package; override {@link #rootPackage()} to change it.
 * Module-specific rules (design.md 11.2 "module isolation") can be added as further {@code @Test}
 * methods in the subclass, using {@link #importClasses()}.
 */
public abstract class AbstractArchitectureTest {

    /** Package whose classes are checked. Defaults to the subclass's package. */
    protected String rootPackage() {
        return getClass().getPackageName();
    }

    /** Production classes of {@link #rootPackage()} (test classes are excluded). */
    protected JavaClasses importClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(rootPackage());
    }

    @Test
    void followsTheArchitectureRules() {
        ArchitectureRules.allRules().check(importClasses());
    }
}
