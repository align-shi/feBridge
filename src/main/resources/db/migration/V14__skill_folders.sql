CREATE TABLE skill_folder (
    skill_id UUID NOT NULL REFERENCES skill_definition(id) ON DELETE CASCADE,
    name VARCHAR(20) NOT NULL CHECK (name IN ('references','assets','scripts')),
    PRIMARY KEY (skill_id,name)
);
INSERT INTO skill_folder(skill_id,name)
SELECT DISTINCT skill_id,split_part(path,'/',1) FROM skill_resource;
