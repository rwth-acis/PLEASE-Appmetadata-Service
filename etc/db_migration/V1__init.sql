-- DROP TABLE IF EXISTS files

-- Beware of setting table and schema names into quotes, as h2 has default option DATABASE_TO_UPPER=true

CREATE TABLE users (
  `oidc_id` VARCHAR(255) NOT NULL
, `username` VARCHAR(255)
, CONSTRAINT pk_users PRIMARY KEY (oidc_id)
);

INSERT INTO users VALUES ('public', 'public');

CREATE TABLE apps (
  `app` INT NOT NULL AUTO_INCREMENT
, `creator` VARCHAR(255)
, `description` VARCHAR(65536)
, `search_text` VARCHAR(65536) -- auto
, `platform` VARCHAR(1024) -- auto
, `config` VARCHAR(65536)
, CONSTRAINT pk_apps PRIMARY KEY (app)
, CONSTRAINT fk_apps_users FOREIGN KEY (creator) REFERENCES users (oidc_id)
);

CREATE TABLE comments (
  `app` INT NOT NULL
, `creator` VARCHAR(255)
, `timestamp` INT(32)
, `text` VARCHAR(1024)
, CONSTRAINT pk_comments PRIMARY KEY (app, creator, timestamp)
, CONSTRAINT fk_comments_apps FOREIGN KEY (app) REFERENCES apps (app)
, CONSTRAINT fk_comments_users FOREIGN KEY (creator) REFERENCES users (oidc_id)
);

CREATE TABLE ratings (
  `app` INT NOT NULL
, `creator` VARCHAR(255)
, `value` INT(32)
, CONSTRAINT fk_ratings_apps FOREIGN KEY (app) REFERENCES apps (app) ON DELETE CASCADE
, CONSTRAINT fk_ratings_users FOREIGN KEY (creator) REFERENCES users (oidc_id)
);

CREATE TABLE media (
  `app` INT NOT NULL
, `name` VARCHAR(255)
, `type` VARCHAR(255)
, `blob` BLOB
, CONSTRAINT pk_media PRIMARY KEY (app, name)
, CONSTRAINT fk_media_apps FOREIGN KEY (app) REFERENCES apps (app) ON DELETE CASCADE
);

CREATE TABLE maintainers (
  `app` INT NOT NULL
, `maintainer` VARCHAR(255)
, CONSTRAINT pk_maintainers PRIMARY KEY (app, maintainer)
, CONSTRAINT fk_maintainers_users FOREIGN KEY (maintainer) REFERENCES users (oidc_id)
, CONSTRAINT fk_maintainers_apps FOREIGN KEY (app) REFERENCES apps (app) ON DELETE CASCADE
);
