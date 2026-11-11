-- Create the board's initial categories, without replacing categories edited later.
INSERT ignore INTO board_categories(category_id,category_name)
SELECT d.id, d.name FROM (VALUES (1,'학교'),(2,'주변'),(3,'취업팁'),(4,'고민상담'),(5,'제안'),(6,'맛집 추천'),(7,'술집 추천'),(8,'가성비'),(9,'공지'),(10,'자유게시판')) d(id,name)
WHERE NOT EXISTS (SELECT 1 FROM catalog_meta WHERE name='hosted-initial-categories')
  AND NOT EXISTS (SELECT 1 FROM board_categories c WHERE c.category_id=d.id OR c.category_name=d.name);
INSERT ignore INTO catalog_meta(name) SELECT 'hosted-initial-categories'
WHERE NOT EXISTS (SELECT 1 FROM catalog_meta WHERE name='hosted-initial-categories');
