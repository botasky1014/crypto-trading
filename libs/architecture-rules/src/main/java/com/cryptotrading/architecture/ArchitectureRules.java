package com.cryptotrading.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.util.List;

/**
 * The general dependency rules of design.md 11.2, for the package layout of design.md 11.1
 * ({@code controller}, {@code service}, {@code service.impl}, {@code mapper}, {@code client},
 * {@code model}).
 *
 * <p>Use them through {@link AbstractArchitectureTest}, or check single rules directly: {@code
 * ArchitectureRules.TRANSACTIONAL_ONLY_IN_SERVICE_IMPL.check(classes)}.
 *
 * <p>Every rule allows an empty selection, because a module does not have to contain every
 * package (e.g. a service without a database has no {@code mapper}).
 */
public final class ArchitectureRules {

    static final String CONTROLLER = "..controller..";
    static final String SERVICE = "..service..";
    static final String SERVICE_IMPL = "..service.impl..";
    static final String MAPPER = "..mapper..";
    static final String CLIENT = "..client..";
    static final String MODEL = "..model..";

    static final String SPRING_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    static final String JAKARTA_TRANSACTIONAL = "jakarta.transaction.Transactional";

    /**
     * Field names that hold an amount, quantity or price (case-insensitive substring match),
     * e.g. {@code qty}, {@code limitPrice}, {@code FEE_RATE}, {@code availableBalance}.
     */
    public static final String MONEY_FIELD_NAME = "(?i).*(amount|qty|price|notional|balance|fee).*";

    private ArchitectureRules() {}

    /** Call direction: a controller goes through a service, never straight to a mapper or client. */
    public static final ArchRule CONTROLLERS_ONLY_CALL_SERVICES = noClasses()
            .that()
            .resideInAPackage(CONTROLLER)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(MAPPER, CLIENT)
            .allowEmptyShould(true)
            .because(
                    "design.md 11.2: controller -> service -> mapper / client; a controller must not skip the service");

    /** No reverse dependency: services do not know about controllers. */
    public static final ArchRule SERVICES_DO_NOT_DEPEND_ON_CONTROLLERS = noClasses()
            .that()
            .resideInAPackage(SERVICE)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(CONTROLLER)
            .allowEmptyShould(true)
            .because("design.md 11.2: dependencies only point from controller to service, never back");

    /** No reverse dependency: mappers and clients do not know about services or controllers. */
    public static final ArchRule MAPPERS_AND_CLIENTS_DO_NOT_DEPEND_ON_UPPER_LAYERS = noClasses()
            .that()
            .resideInAnyPackage(MAPPER, CLIENT)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(SERVICE, CONTROLLER)
            .allowEmptyShould(true)
            .because("design.md 11.2: mapper and client are the lowest layer");

    /** {@code @Transactional} (Spring or Jakarta) on a type only in {@code service.impl}. */
    public static final ArchRule TRANSACTIONAL_TYPES_ONLY_IN_SERVICE_IMPL = noClasses()
            .that()
            .resideOutsideOfPackage(SERVICE_IMPL)
            .and()
            .areNotAnnotations()
            .should()
            .beMetaAnnotatedWith(SPRING_TRANSACTIONAL)
            .orShould()
            .beMetaAnnotatedWith(JAKARTA_TRANSACTIONAL)
            .allowEmptyShould(true)
            .because("design.md 11.2: transaction boundaries are declared only in service.impl");

    /** {@code @Transactional} (Spring or Jakarta) on a method only in {@code service.impl}. */
    public static final ArchRule TRANSACTIONAL_METHODS_ONLY_IN_SERVICE_IMPL = noMethods()
            .that()
            .areDeclaredInClassesThat()
            .resideOutsideOfPackage(SERVICE_IMPL)
            .should()
            .beMetaAnnotatedWith(SPRING_TRANSACTIONAL)
            .orShould()
            .beMetaAnnotatedWith(JAKARTA_TRANSACTIONAL)
            .allowEmptyShould(true)
            .because("design.md 11.2: transaction boundaries are declared only in service.impl");

    /**
     * {@code @Audited} only on methods in {@code service.impl}. Matched by simple name until the
     * annotation exists in {@code common-audit} (P6).
     */
    public static final ArchRule AUDITED_ONLY_IN_SERVICE_IMPL = noMethods()
            .that()
            .areDeclaredInClassesThat()
            .resideOutsideOfPackage(SERVICE_IMPL)
            .should()
            .beMetaAnnotatedWith(simpleName("Audited"))
            .allowEmptyShould(true)
            .because("design.md 11.2 / 5.9.3: audit records are written in the business transaction of service.impl");

    /** Model classes are plain data: no service, controller or mapper dependency. */
    public static final ArchRule MODEL_IS_PURE = noClasses()
            .that()
            .resideInAPackage(MODEL)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(SERVICE, CONTROLLER, MAPPER)
            .allowEmptyShould(true)
            .because("design.md 11.2: model must not depend on service, controller or mapper");

    /** Amounts, quantities and prices are never binary floating point. */
    public static final ArchRule NO_FLOATING_POINT_MONEY = noFields()
            .that()
            .haveNameMatching(MONEY_FIELD_NAME)
            .should()
            .haveRawType(double.class)
            .orShould()
            .haveRawType(float.class)
            .orShould()
            .haveRawType(Double.class)
            .orShould()
            .haveRawType(Float.class)
            .orShould()
            .haveRawType(double[].class)
            .orShould()
            .haveRawType(float[].class)
            .allowEmptyShould(true)
            .because("design.md 11.2: amounts, quantities and prices use BigDecimal, never double / float");

    /** Every rule above. */
    public static List<ArchRule> all() {
        return List.of(
                CONTROLLERS_ONLY_CALL_SERVICES,
                SERVICES_DO_NOT_DEPEND_ON_CONTROLLERS,
                MAPPERS_AND_CLIENTS_DO_NOT_DEPEND_ON_UPPER_LAYERS,
                TRANSACTIONAL_TYPES_ONLY_IN_SERVICE_IMPL,
                TRANSACTIONAL_METHODS_ONLY_IN_SERVICE_IMPL,
                AUDITED_ONLY_IN_SERVICE_IMPL,
                MODEL_IS_PURE,
                NO_FLOATING_POINT_MONEY);
    }

    /** All rules as one rule, so a single check reports every violation. */
    public static ArchRule allRules() {
        return CompositeArchRule.of(all());
    }

    private static DescribedPredicate<JavaAnnotation<?>> simpleName(String name) {
        return DescribedPredicate.describe(
                "@" + name,
                annotation -> annotation.getRawType().getSimpleName().equals(name));
    }
}
