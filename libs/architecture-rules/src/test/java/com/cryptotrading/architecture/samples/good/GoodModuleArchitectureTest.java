package com.cryptotrading.architecture.samples.good;

import com.cryptotrading.architecture.AbstractArchitectureTest;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/** Shows how a service uses the base class; samples are test classes, so the import is widened. */
class GoodModuleArchitectureTest extends AbstractArchitectureTest {

    @Override
    protected JavaClasses importClasses() {
        return new ClassFileImporter().importPackages(rootPackage());
    }
}
