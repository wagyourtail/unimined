package xyz.wagyourtail.unimined.fmlloader;

import net.minecraftforge.fml.loading.targets.ForgeClientUserdevLaunchHandler;

public class UniminedClientUserdev extends ForgeClientUserdevLaunchHandler {
    @Override
    public String getNaming() {
        return "unimined";
    }

    @Override
    public String name() {
        return "uniminedclientuserdev";
    }
}
