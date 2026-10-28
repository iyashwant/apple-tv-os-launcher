"""Header shortcuts keep configured package-scoped actions in the fork controller."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "launcher/app/src/main/java/com/gathin/tvhome"
class TopBarLaunchActionsTest(unittest.TestCase):
    def setUp(self):
        self.source=(JAVA/"IntegrationController.java").read_text()
    def test_controller_uses_shared_launch_policy_after_checking_app_exists(self):
        self.assertIn("LaunchActions.select(pkg,config.launchActions,launch,",self.source)
        self.assertLess(self.source.index("if (launch==null) continue"),self.source.index("LaunchActions.select"))
        self.assertIn("i.resolveActivity(home.getPackageManager())!=null?i:null",self.source)
        self.assertIn("LaunchActions.intent(p,a)",self.source)
        self.assertIn("home.openInput(input)",self.source)
    def test_action_stays_package_scoped_and_normal_launch_keeps_both_categories(self):
        self.assertIn("return i.setPackage(pkg);",(JAVA/"LaunchActions.java").read_text())
        normal=(JAVA/"OpenOnStart.java").read_text()
        self.assertIn("getLeanbackLaunchIntentForPackage(pkg)",normal)
        self.assertIn("getLaunchIntentForPackage(pkg)",normal)
