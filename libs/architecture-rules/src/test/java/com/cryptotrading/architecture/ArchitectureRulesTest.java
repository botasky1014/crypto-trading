package com.cryptotrading.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Each rule is shown to reject its counter-example (samples.bad.*) and to accept the compliant
 * sample module (samples.good).
 */
class ArchitectureRulesTest {

    private static final String SAMPLES = "com.cryptotrading.architecture.samples";

    static Stream<Arguments> counterExamples() {
        return Stream.of(
                Arguments.of(
                        "CONTROLLERS_ONLY_CALL_SERVICES / mapper",
                        ArchitectureRules.CONTROLLERS_ONLY_CALL_SERVICES,
                        "controllercallsmapper",
                        "OrderMapper"),
                Arguments.of(
                        "CONTROLLERS_ONLY_CALL_SERVICES / client",
                        ArchitectureRules.CONTROLLERS_ONLY_CALL_SERVICES,
                        "controllercallsclient",
                        "LedgerClient"),
                Arguments.of(
                        "SERVICES_DO_NOT_DEPEND_ON_CONTROLLERS",
                        ArchitectureRules.SERVICES_DO_NOT_DEPEND_ON_CONTROLLERS,
                        "serviceusescontroller",
                        "OrderController"),
                Arguments.of(
                        "MAPPERS_AND_CLIENTS_DO_NOT_DEPEND_ON_UPPER_LAYERS",
                        ArchitectureRules.MAPPERS_AND_CLIENTS_DO_NOT_DEPEND_ON_UPPER_LAYERS,
                        "mapperusesservice",
                        "OrderService"),
                Arguments.of(
                        "TRANSACTIONAL_TYPES_ONLY_IN_SERVICE_IMPL",
                        ArchitectureRules.TRANSACTIONAL_TYPES_ONLY_IN_SERVICE_IMPL,
                        "transactionalclass",
                        "AccountService"),
                Arguments.of(
                        "TRANSACTIONAL_METHODS_ONLY_IN_SERVICE_IMPL",
                        ArchitectureRules.TRANSACTIONAL_METHODS_ONLY_IN_SERVICE_IMPL,
                        "transactionalmethod",
                        "AccountController.open"),
                Arguments.of(
                        "AUDITED_ONLY_IN_SERVICE_IMPL",
                        ArchitectureRules.AUDITED_ONLY_IN_SERVICE_IMPL,
                        "auditedoutsideimpl",
                        "ResetListener.onReset"),
                Arguments.of("MODEL_IS_PURE", ArchitectureRules.MODEL_IS_PURE, "modelusesservice", "OrderEntity"),
                Arguments.of(
                        "NO_FLOATING_POINT_MONEY / double",
                        ArchitectureRules.NO_FLOATING_POINT_MONEY,
                        "floatingpointmoney",
                        "PlaceOrderRequest.qty"),
                Arguments.of(
                        "NO_FLOATING_POINT_MONEY / Float",
                        ArchitectureRules.NO_FLOATING_POINT_MONEY,
                        "floatingpointmoney",
                        "PlaceOrderRequest.limitPrice"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("counterExamples")
    void ruleRejectsItsCounterExample(String name, ArchRule rule, String samplePackage, String expectedInReport) {
        EvaluationResult result = rule.evaluate(importSamples("bad." + samplePackage));

        assertThat(result.hasViolation()).as("violation expected").isTrue();
        assertThat(result.getFailureReport().toString()).contains(expectedInReport);
    }

    @Test
    void everyRuleHasACounterExample() {
        assertThat(counterExamples().map(args -> args.get()[1]).distinct()).hasSameSizeAs(ArchitectureRules.all());
    }

    @Test
    void compliantModulePassesEveryRule() {
        JavaClasses good = importSamples("good");

        for (ArchRule rule : ArchitectureRules.all()) {
            EvaluationResult result = rule.evaluate(good);
            assertThat(result.hasViolation())
                    .as(result.getFailureReport().toString())
                    .isFalse();
        }
    }

    @Test
    void emptyModulePassesEveryRule() {
        ArchitectureRules.allRules().check(importSamples("doesnotexist"));
    }

    @Test
    void compositeRuleReportsEveryCounterExample() {
        EvaluationResult result = ArchitectureRules.allRules().evaluate(importSamples("bad"));

        assertThat(result.getFailureReport().getDetails())
                .hasSizeGreaterThanOrEqualTo((int) counterExamples().count());
    }

    private static JavaClasses importSamples(String subPackage) {
        // Samples are test classes, so the importer must not filter test locations here.
        return new ClassFileImporter().importPackages(SAMPLES + "." + subPackage);
    }
}
