package com.dia.ismdtoolbackend.utility.paging;

import com.dia.ismdtoolbackend.controller.dto.PageDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageParamsTest {

    @ParameterizedTest
    @CsvSource({"0,0", "101,0", "20,-1"})
    void rejectsAnOutOfRangePage(int limit, int offset) {
        assertThatThrownBy(() -> new PageParams(limit, offset)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsTheBounds() {
        new PageParams(1, 0);
        new PageParams(PageParams.MAX_LIMIT, Integer.MAX_VALUE);
    }

    @Test
    void sliceCutsTheWindowAndCountsTheWhole() {
        assertThat(new PageParams(2, 1).slice(List.of("a", "b", "c", "d")))
                .isEqualTo(new PageDto<>(List.of("b", "c"), 4, 2, 1));
    }

    @Test
    void sliceHonoursAnOffsetThatIsNotAPageMultiple() {
        assertThat(new PageParams(3, 2).slice(List.of("a", "b", "c", "d")).items()).containsExactly("c", "d");
    }

    @Test
    void sliceBeyondTheEndIsEmptyButStillCounts() {
        assertThat(new PageParams(20, 10).slice(List.of("a")))
                .isEqualTo(new PageDto<>(List.of(), 1, 20, 10));
    }

    @Test
    void sliceNearIntegerOverflowDoesNotWrap() {
        assertThat(new PageParams(100, Integer.MAX_VALUE).slice(List.of("a")).items()).isEmpty();
    }

    @Test
    void pageableIsAddressedByOffset() {
        OffsetPageable pageable = new PageParams(8, 12).pageable();

        assertThat(pageable.getOffset()).isEqualTo(12);
        assertThat(pageable.getPageSize()).isEqualTo(8);
        assertThat(pageable.getSort().isUnsorted()).isTrue();
        assertThat(pageable.next().getOffset()).isEqualTo(20);
        assertThat(pageable.previousOrFirst().getOffset()).isEqualTo(4);
        assertThat(new OffsetPageable(8, 3).previousOrFirst().getOffset()).isZero();
        assertThat(new OffsetPageable(8, 0).previousOrFirst().getOffset()).isZero();
        assertThat(pageable.withPage(2).getOffset()).isEqualTo(16);
        assertThat(pageable.first().getOffset()).isZero();
        assertThat(pageable.getPageNumber()).isEqualTo(1);
    }
}
