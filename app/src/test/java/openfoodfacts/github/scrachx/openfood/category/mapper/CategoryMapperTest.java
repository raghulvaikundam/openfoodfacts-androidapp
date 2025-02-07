package openfoodfacts.github.scrachx.openfood.category.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

import openfoodfacts.github.scrachx.openfood.category.model.Category;
import openfoodfacts.github.scrachx.openfood.category.network.CategoryResponse;
import openfoodfacts.github.scrachx.openfood.utils.FileTestUtils;

import static org.junit.Assert.*;

/**
 * Created by Abdelali Eramli on 01/01/2018.
 */

public class CategoryMapperTest {

    @Test
    public void fromNetworkFullResponseCategoryList() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        CategoryResponse response = mapper.readValue(FileTestUtils
                .readTextFileFromResources("mock_categories.json", this.getClass().getClassLoader()), CategoryResponse.class);
        List<Category> categories = new CategoryMapper().fromNetwork(response.getTags());
        assertEquals(response.getTags().size(), categories.size());
    }
}
