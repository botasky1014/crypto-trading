package com.cryptotrading.common.mybatis.fill;

/**
 * Who is writing: the value of {@code created_by} / {@code updated_by}.
 *
 * <p>The default ({@link SecurityContextActorProvider}) uses the authenticated user's ID (the JWT
 * {@code sub}) and falls back to {@code system:<spring.application.name>} for scheduled jobs,
 * Kafka listeners and other work without a user. P6 ({@code AuditContext.runAsSystem}) replaces
 * the bean to support named system tasks such as {@code system:ledger-reconcile}.
 */
@FunctionalInterface
public interface ActorProvider {

    String SYSTEM_PREFIX = "system:";

    String currentActor();
}
