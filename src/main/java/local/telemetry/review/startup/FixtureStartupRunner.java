package local.telemetry.review.startup;

import local.telemetry.review.fixture.FixtureImportService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class FixtureStartupRunner implements ApplicationRunner {
  private final FixtureImportService fixtureImportService;

  public FixtureStartupRunner(FixtureImportService fixtureImportService) {
    this.fixtureImportService = fixtureImportService;
  }

  @Override
  public void run(ApplicationArguments args) {
    fixtureImportService.ensureFixtureImported();
  }
}
