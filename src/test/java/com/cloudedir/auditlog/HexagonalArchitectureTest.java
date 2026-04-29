package com.cloudedir.auditlog;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.cloudedir.auditlog")
class HexagonalArchitectureTest {

  private static final String DOMAIN = "com.cloudedir.auditlog.domain..";
  private static final String APPLICATION = "com.cloudedir.auditlog.application..";
  private static final String INFRASTRUCTURE = "com.cloudedir.auditlog.infrastructure..";
  private static final String API = "com.cloudedir.auditlog.api..";

  @ArchTest
  ArchRule domainHasNoDependencyOnOtherLayers =
      noClasses()
          .that()
          .resideInAPackage(DOMAIN)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(APPLICATION, INFRASTRUCTURE, API);

  @ArchTest
  ArchRule applicationHasNoDependencyOnInfrastructureOrApi =
      noClasses()
          .that()
          .resideInAPackage(APPLICATION)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(INFRASTRUCTURE, API);

  @ArchTest
  ArchRule apiHasNoDependencyOnInfrastructure =
      noClasses()
          .that()
          .resideInAPackage(API)
          .should()
          .dependOnClassesThat()
          .resideInAPackage(INFRASTRUCTURE);

  @ArchTest
  ArchRule portsResideInApplicationLayer =
      classes()
          .that()
          .resideInAPackage("com.cloudedir.auditlog.application.port..")
          .and()
          .areNotRecords()
          .should()
          .beInterfaces();

  @ArchTest
  ArchRule domainModelAreRecordsOrEnums =
      classes()
          .that()
          .resideInAPackage("com.cloudedir.auditlog.domain.model..")
          .should()
          .beRecords();
}
