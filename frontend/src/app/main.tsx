import { BrowserRouter } from "react-router-dom";
import { ApplicationRouter } from "./router";
import { mountApplication } from "./bootstrap";

mountApplication(
  <BrowserRouter>
    <ApplicationRouter />
  </BrowserRouter>,
);
