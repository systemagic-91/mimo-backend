package br.com.luma;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;
import lombok.Getter;

class BuildToolingTests {

    @Test
    void lombokAndMapstructGenerateUsableCode() {
        Source source = new Source();
        source.setName("Luma");

        Target target = Mappers.getMapper(SourceMapper.class).toTarget(source);

        assertThat(source.getName()).isEqualTo("Luma");
        assertThat(target.getName()).isEqualTo("Luma");
    }

    @Getter
    static class Source {
        private String name;

        public void setName(String name) {
            this.name = name;
        }
    }

    @Getter
    static class Target {
        private String name;

        public void setName(String name) {
            this.name = name;
        }
    }

    @Mapper
    interface SourceMapper {
        Target toTarget(Source source);
    }
}