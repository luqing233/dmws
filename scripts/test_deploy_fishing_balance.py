import unittest
from unittest.mock import patch
from deploy_fishing_balance import migrate,projections

class FakeDatabase:
    def __init__(self):self.columns=set();self.statements=[]
    def sql(self,statement,database='dmw'):
        self.statements.append((statement,database))
        if statement.startswith('SELECT'):return '1' if statement.split("COLUMN_NAME='")[1].split("'")[0] in self.columns else '0'
        self.columns.add(statement.split('`')[1]);return ''
    def tables(self,schema):return ['economy_fishing_attempt','economy_item_definition','economy_fishing_event']

class FishingBalanceDeploymentTests(unittest.TestCase):
    def test_only_adds_eight_nullable_columns_and_repeated_migration_is_safe(self):
        db=FakeDatabase();migrate(db,'dmw_fishing_restore_20261109003000');migrate(db,'dmw_fishing_restore_20261109003000')
        writes=[s for s,_ in db.statements if not s.startswith('SELECT')]
        self.assertEqual(8,len(writes));self.assertTrue(all(s.startswith('ALTER TABLE economy_fishing_attempt ADD COLUMN') and s.endswith(' NULL') for s in writes))
    def test_rejects_other_schema_without_sql(self):
        db=FakeDatabase()
        with self.assertRaises(ValueError):migrate(db,'hy_economy')
        self.assertEqual([],db.statements)
    def test_preservation_checks_cover_every_existing_row_and_column(self):
        with patch('deploy_fishing_balance.source_expression',return_value=('original_columns','key')):
            snapshots=projections(FakeDatabase(),'dmw')
        self.assertTrue(all(p==('original_columns','key','') for p in snapshots.values()))

if __name__=='__main__':unittest.main()
