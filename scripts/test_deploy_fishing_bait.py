import unittest
from deploy_fishing_bait import migrate
class FakeDatabase:
    def __init__(self):
        self.statements=[]
        self.present=False
    def sql(self,statement,database="dmw"):
        self.statements.append((statement,database))
        if statement.startswith("SELECT"):return "1" if self.present else "0"
        self.present=True
        return ""

class BaitDeploymentTests(unittest.TestCase):
    def test_only_adds_nullable_audit_column_and_never_rewrites_existing_records(self):
        db=FakeDatabase()
        migrate(db,'dmw_fishing_restore_20261108233000')
        migrate(db,'dmw_fishing_restore_20261108233000')
        writes=[s for s,_ in db.statements if not s.startswith('SELECT')]
        self.assertEqual(['ALTER TABLE economy_fishing_attempt ADD COLUMN bait_quality varchar(16) NULL'],writes)
        self.assertFalse(any(word in s for s in writes for word in ['UPDATE','DELETE','TRUNCATE','DROP']))
    def test_rejects_other_schema(self):
        db=FakeDatabase()
        with self.assertRaises(ValueError): migrate(db,'economy')
        self.assertEqual([],db.statements)

if __name__=='__main__':unittest.main()
